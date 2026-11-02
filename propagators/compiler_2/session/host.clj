(ns propagators.compiler-2.session.host
  "Post-commit delivery and later-round input admission for compiler sessions.
  This is host infrastructure, not a workflow or another session abstraction."
  (:require [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.runtime.boundary.effects :as effects]
            [propagators.compiler-2.session :as session]
            [propagators.compiler-2.session.dependencies :as dependency]
            [propagators.compiler-2.session.files :as files]
            [propagators.compiler-2.session.operators :as operators]
            [propagators.message :as message])
  (:import [java.util UUID]
           [java.util.concurrent Executors ExecutorService ThreadFactory TimeUnit FutureTask Callable TimeoutException
                                  ThreadPoolExecutor ArrayBlockingQueue RejectedExecutionException]))

(deftype SessionBoundary [roundGate gate view executor deliveries capacity automatic cleanupTimeout])

(defn runtime-session [host] (.-view ^SessionBoundary host))
(defn boundary-for [runtime-session] (::boundary (meta runtime-session)))
(defn- gate [host] (.-gate ^SessionBoundary host))
(defn- round-gate [host] (.-roundGate ^SessionBoundary host))

(def ^:private host-keys
  [::session ::pending ::receipts ::closed? ::phase ::scheduled? ::blocked? ::failures ::delivery-ready?])

(defn current-session [host]
  (let [view @(runtime-session host)
        compiler-session (::session view)
        current-state (apply dissoc view host-keys)]
    (if (= current-state (session/compiler-state compiler-session))
      compiler-session
      (session/with-compiler-state compiler-session current-state))))

(defn- publish! [host compiler-session]
  (let [view (runtime-session host)]
    (reset! view (merge (session/compiler-state compiler-session)
                        (select-keys @view host-keys)
                        {::session compiler-session}))))

(defn receipts [host] (::receipts @(runtime-session host)))
(defn pending-count [host] (count (::pending @(runtime-session host))))
(defn failures [host] (::failures @(runtime-session host)))

(defn- require-open! [host]
  (when (::closed? @(runtime-session host))
    (throw (ex-info "Compiler session boundary is closed" {}))))

(defn- validate-dependencies! [compiler-session]
  (doseq [[id capability] (session/dependency-declarations compiler-session)]
    (when-not (satisfies? dependency/Dependency capability)
      (throw (ex-info "Dependency must implement Dependency" {:dependency id})))))

(defn- live-request? [host request]
  (let [current (current-session host)]
    (and (= (:run-id (session/session-identity current))
            (get-in request [:boundary/payload :run-id]))
         (or (get-in request [:boundary/payload :subscription])
             (operators/eligible? (session/network current) request)))))

(declare step! wake! complete!)

(defn submit!
  "Normalize external ingress before admission. Never advance from a callback."
  [host input]
  (let [input (session/normalize-input input)
        result (locking (gate host)
                 (require-open! host)
                 (if (< (pending-count host) (.-capacity ^SessionBoundary host))
                   (do (swap! (runtime-session host) update ::pending conj {:input input})
                       :accepted)
                   :backpressured))]
    (when (= :accepted result) (wake! host))
    result))

(defn- normalized-replies [host request normalized]
  (let [output (:boundary/receipt-id request)
        input-ids (get-in request [:boundary/payload :input-ids])]
    (if (or (message/message? normalized) (:runtime/input normalized))
      (let [input (session/normalize-input normalized)]
        (when (and output (not= output (:cell-id input)))
          (throw (ex-info "Reply targets an inlet outside its invocation" {})))
        [input])
      (if output
        (mapv session/normalize-input
              (basis/primitive-reply-messages
               (session/network (current-session host)) input-ids output normalized))
        (throw (ex-info "Subscription output must normalize to an ordinary cell message" {}))))))

(defn- accepts-output? [host request]
  (and (not (::closed? @(runtime-session host)))
       (#{:starting :active} (get-in (receipts host) [(:boundary/id request) :status]))
       (live-request? host request)))

(defn- emit! [host request capability external-input]
  (if-not (locking (gate host) (accepts-output? host request))
    :closed
    (let [normalized
          (try (dependency/normalize-output capability external-input)
               (catch Exception error
                 (locking (gate host)
                   (swap! (runtime-session host) assoc-in
                          [::receipts (:boundary/id request) :input-error] error))
                 (throw error)))
          result
          (locking (gate host)
            (if-not (accepts-output? host request)
              :closed
              (let [inputs (normalized-replies host request normalized)]
                (if (> (+ (pending-count host) (count inputs))
                       (.-capacity ^SessionBoundary host))
                  :backpressured
                  (do
                    (swap! (runtime-session host) update ::pending into
                           (mapv #(hash-map :input % :request request) inputs))
                    :accepted)))))]
      (when (= :accepted result) (wake! host))
      result)))

(defn- cleanup! [host id]
  (let [stop (locking (gate host)
               (let [receipt (get-in @(runtime-session host) [::receipts id])]
                 (when (and (:start-finished? receipt)
                            (:stop receipt)
                            (= :pending (:cleanup receipt)))
                   (swap! (runtime-session host) assoc-in [::receipts id :cleanup] :running)
                   (:stop receipt))))]
    (when stop
      (let [work (FutureTask. ^Callable (reify Callable (call [_] (stop))))
            worker (doto (Thread. work "lain-dependency-cleanup") (.setDaemon true))
            _ (.start worker)
            outcome (try
                      (let [value (.get work (long (.-cleanupTimeout ^SessionBoundary host))
                                        TimeUnit/MILLISECONDS)]
                        (if (= :closed (:status value))
                          {:cleanup :closed}
                          {:cleanup :failed :cleanup-result value}))
                      (catch TimeoutException _
                        (.cancel work true)
                        {:cleanup :failed :cleanup-result ::timeout})
                      (catch Exception error {:cleanup :failed :cleanup-error error}))]
        (locking (gate host)
          (swap! (runtime-session host) update-in [::receipts id] merge outcome))
        (when (= :failed (:cleanup outcome))
          (throw (ex-info "Dependency cleanup was not verified" {:id id :outcome outcome})))))))

(defn complete! [host id]
  (locking (gate host)
    (when (#{:starting :active} (get-in @(runtime-session host) [::receipts id :status]))
      (swap! (runtime-session host) assoc-in [::receipts id :status] :completed)))
  (cleanup! host id))

(defn cancel! [host id]
  (locking (gate host)
    (when (#{:starting :active} (get-in @(runtime-session host) [::receipts id :status]))
      (swap! (runtime-session host) assoc-in [::receipts id :status] :cancelled)))
  (cleanup! host id)
  (get (receipts host) id))

(defn reconcile!
  "Run explicit transport reconciliation; never automatically repeat an uncertain launch.
  reconcile must verify absence/cleanup and return {:status :closed}."
  [host id reconcile]
  (when-not (fn? reconcile)
    (throw (ex-info "Reconciliation requires a function" {})))
  (let [prior (locking (gate host)
                (let [receipt (get (receipts host) id)]
                  (when-not (or (= :uncertain (:status receipt))
                                (= :failed (:cleanup receipt)))
                    (throw (ex-info "Receipt does not require reconciliation" {:id id})))
                  (swap! (runtime-session host) assoc-in [::receipts id :status] :reconciling)
                  receipt))]
    (try
      (let [result (reconcile (:request prior))]
        (when-not (= :closed (:status result))
          (throw (ex-info "Reconciliation did not verify cleanup" {:id id :result result})))
        (locking (gate host)
          (swap! (runtime-session host) update-in [::receipts id] merge
                 {:status :reconciled :cleanup :closed :reconciliation result}))
        result)
      (catch Exception error
        (locking (gate host)
          (swap! (runtime-session host) assoc-in [::receipts id]
                 (assoc prior :reconciliation-error error)))
        (throw error)))))

(defn- start-claimed! [host request capability]
  (let [id (:boundary/id request)
        start? (locking (gate host)
                 (if (and (not (::closed? @(runtime-session host)))
                          (= :starting (get-in (receipts host) [id :status]))
                          (live-request? host request))
                   (do (swap! (runtime-session host) assoc-in [::receipts id :invoking?] true)
                       true)
                   (do (swap! (runtime-session host) update-in [::receipts id] merge
                              {:status :cancelled :start-finished? true :cleanup :not-required})
                       false)))]
    (when start?
      (try
        (let [context (assoc (dissoc (:boundary/payload request) :capability :interpreter :effect-input)
                             :id id
                             :complete! #(complete! host id)
                             :cancelled? #(not (#{:starting :active}
                                                (get-in (receipts host) [id :status]))))
              stop (dependency/start! capability context #(emit! host request capability %))]
          (when-not (or (nil? stop) (fn? stop))
            (throw (ex-info "Dependency must return nil or a cleanup function" {:id id})))
          (let [terminal?
                (locking (gate host)
                  (swap! (runtime-session host) update-in [::receipts id]
                         (fn [receipt]
                           (cond-> (assoc receipt :start-finished? true :stop stop
                                                  :cleanup (if stop :pending :not-required))
                             (= :starting (:status receipt)) (assoc :status :active))))
                  (not= :active (get-in (receipts host) [id :status])))]
            (when terminal? (cleanup! host id))))
        (catch Exception error
          (locking (gate host)
            (swap! (runtime-session host)
                   #(-> %
                        (update-in [::receipts id]
                                   (fn [receipt]
                                     (if (:start-finished? receipt)
                                       (assoc receipt :error error)
                                       (merge receipt {:status :uncertain :start-finished? true
                                                       :error error :cleanup :failed}))))
                        (update ::failures conj error)))))
        (finally
          (locking (gate host)
            (swap! (runtime-session host) assoc ::delivery-ready? true))
          (wake! host))))))

(deftype HostEffectInterpreter [view route])

(extend-type HostEffectInterpreter
  operators/EffectInterpreter
  (interpret-effect! [interpreter owner context input emit!]
    (let [view (.-view ^HostEffectInterpreter interpreter)]
      (when-not (identical? view (runtime-session owner))
        (throw (ex-info "Effect interpreter belongs to another session boundary" {})))
      (require-open! owner)
      (let [routed ((.-route ^HostEffectInterpreter interpreter) input)]
        (case (operators/effect-route routed)
          :next-round
          (let [result (emit! (operators/effect-input-value routed))]
            (when-not (= :accepted result)
              (throw (ex-info "Interpreter input was not admitted" {:status result})))
            ((:complete! context))
            nil)

          :dependency
          (let [id (operators/effect-dependency routed)
                capability (get (session/dependency-declarations (current-session owner)) id)]
            (when-not capability
              (throw (ex-info "Interpreter references an undeclared dependency" {:dependency id})))
            (dependency/start! capability
                               (assoc context :input (operators/effect-input-value routed))
                               #(emit! (dependency/normalize-output capability %)))))))))

(defn effect-interpreter
  "Inject this boundary's existing ingress atom and live dependency catalog.
  route is a pure policy producing next-round-input or dependency-input.
  The interpreter never advances a session or starts another input queue."
  ([host] (effect-interpreter host identity))
  ([host route]
   (when-not (and (instance? SessionBoundary host) (fn? route))
     (throw (ex-info "Effect interpreter requires a session boundary and pure routing function" {})))
   (require-open! host)
   (HostEffectInterpreter. (runtime-session host) route)))

(defn- request-capability [host request]
  (let [{:keys [interpreter effect-input dependency]} (:boundary/payload request)]
    (cond
      interpreter
      (dependency/callback-dependency
       (fn [context emit!]
         (operators/interpret-effect! interpreter host context effect-input emit!)))
      :else (get (session/dependency-declarations (current-session host)) dependency))))

(defn- dispatch! [host request]
  (let [id (:boundary/id request)
        capability
        (locking (gate host)
          (require-open! host)
          (when (and (not (contains? (receipts host) id)) (live-request? host request))
            (let [capability (request-capability host request)]
              (when-not capability
                (throw (ex-info "Unknown dependency in callback request" {:id id})))
              (swap! (runtime-session host) assoc-in [::receipts id]
                     {:status :starting :request request :cleanup :pending})
              capability)))]
    (when capability
      (try
        (.execute ^ExecutorService (.-deliveries ^SessionBoundary host)
                  ^Runnable #(start-claimed! host request capability))
        :admitted
        (catch RejectedExecutionException _
          ;; No invocation occurred: leave the intent eligible in the Lain outbox.
          (locking (gate host)
            (swap! (runtime-session host) update ::receipts dissoc id))
          :backpressured)))))

(defn- admitted-inputs [host queued]
  (->> queued
       (filter (fn [{:keys [request]}]
                 (or (nil? request)
                     (and (live-request? host request)
                          (#{:active :starting :completed}
                           (get-in (receipts host) [(:boundary/id request) :status]))))))
       (mapv :input)))

(defn step!
  "Commit one admitted batch, then deliver current intents. Replies stay queued."
  [host]
  (locking (round-gate host)
   (let [requests
        (locking (gate host)
          (require-open! host)
          (let [view (runtime-session host)]
            (when-not (= :idle (::phase @view))
              (throw (ex-info "Compiler session round is already active" {})))
            (let [result (session/advance (current-session host)
                                          (admitted-inputs host (::pending @view)))]
              (publish! host (:session result))
              (swap! view assoc ::pending (mapv #(hash-map :input %) (:next-inputs result))
                     ::phase :delivering ::blocked? false)
              (:requests result))))]
    (try
      ((requiring-resolve 'propagators.compiler-2.runtime/schedule-clock-subscriptions!)
       (runtime-session host))
      ((requiring-resolve 'propagators.compiler-2.runtime/refresh-trace-subscriptions!)
       (runtime-session host))
      (doseq [request requests :when (operators/callback-request? request)]
        (dispatch! host request))
      (current-session host)
      (finally
        (locking (gate host)
          (swap! (runtime-session host) assoc ::phase :idle))
        (wake! host))))))

(defn wake! [host]
  (when (.-automatic ^SessionBoundary host)
    (let [schedule?
          (locking (gate host)
            (let [view (runtime-session host)]
              (when (and (not (::closed? @view)) (not (::blocked? @view))
                         (= :idle (::phase @view))
                         (not (::scheduled? @view))
                         (or (pos? (pending-count host)) (::delivery-ready? @view)))
                (swap! view assoc ::scheduled? true ::delivery-ready? false)
                true)))]
      (when schedule?
        (.submit ^ExecutorService (.-executor ^SessionBoundary host)
                 ^Runnable
                 (fn []
                   (try
                     (step! host)
                     (catch Exception error
                       (locking (gate host)
                         (swap! (runtime-session host)
                                #(-> % (assoc ::blocked? true)
                                     (update ::failures conj error)))))
                     (finally
                       (locking (gate host)
                         (swap! (runtime-session host) assoc ::scheduled? false))
                       (wake! host)))))))))

(defn open!
  "Attach host services without launching requests. Call step! to start the run."
  ([compiler-session] (open! compiler-session {}))
  ([compiler-session {:keys [capacity auto-advance? cleanup-timeout-ms delivery-threads]
                      :or {capacity 256 auto-advance? true cleanup-timeout-ms 5000 delivery-threads 4}}]
   (validate-dependencies! compiler-session)
   (when-not (and (pos-int? capacity) (pos-int? cleanup-timeout-ms) (pos-int? delivery-threads))
     (throw (ex-info "Positive boundary capacity and cleanup timeout required" {})))
   (let [view (atom (merge (session/compiler-state compiler-session)
                           {::session compiler-session ::pending [] ::receipts {}
                            ::phase :idle ::closed? false ::scheduled? false
                            ::blocked? false ::failures [] ::delivery-ready? false}))
         executor (Executors/newSingleThreadExecutor
                   (reify ThreadFactory
                     (newThread [_ runnable]
                       (doto (Thread. runnable "lain-session-inputs") (.setDaemon true)))))
         deliveries (ThreadPoolExecutor.
                     (int delivery-threads) (int delivery-threads) 0 TimeUnit/MILLISECONDS
                     (ArrayBlockingQueue. (int capacity))
                     (reify ThreadFactory
                       (newThread [_ runnable]
                         (doto (Thread. runnable "lain-dependency-delivery") (.setDaemon true)))))
         host (SessionBoundary. (Object.) view view executor deliveries
                                capacity auto-advance? cleanup-timeout-ms)]
     (alter-meta! view assoc ::boundary host)
     host)))

(defn extend! [host extension]
  (locking (round-gate host)
    (locking (gate host)
      (require-open! host)
      (when-not (= :idle (::phase @(runtime-session host)))
        (throw (ex-info "Cannot extend during dependency dispatch" {})))
      (let [candidate (session/extend-session (current-session host) extension)]
        (validate-dependencies! candidate)
        (publish! host candidate)))
    (step! host)))

(defn extend-source!
  "Editor/CLI source ingress: snapshot imports before publishing an atomic extension."
  [host text]
  (let [current (current-session host)
        file (str "session-extension/" (UUID/randomUUID) ".lain")
        extension (files/prepare-extension!
                   {:id [:source-extension file]
                    :files (assoc (session/source-files current) file text)})]
    (extend! host extension)))

(defn- await-start! [host id]
  (let [deadline (+ (System/nanoTime)
                    (* 1000000 (long (.-cleanupTimeout ^SessionBoundary host))))]
    (loop []
      (when-not (get-in (receipts host) [id :start-finished?])
        (when (> (System/nanoTime) deadline)
          (throw (ex-info "Dependency has not retired" {:id id})))
        (Thread/sleep 2)
        (recur)))))

(defn rerun!
  "Explicitly retire the old run before starting a fresh value from the same files."
  [host fresh-run-id]
  (locking (round-gate host)
    (let [candidate (locking (gate host)
                      (require-open! host)
                      (when-not (= :idle (::phase @(runtime-session host)))
                        (throw (ex-info "Cannot rerun during dependency dispatch" {})))
                      (let [candidate (session/rerun (current-session host) fresh-run-id)]
                        (swap! (runtime-session host) assoc ::phase :rerunning)
                        candidate))]
      (try
        (doseq [[id receipt] (receipts host)]
          (when (#{:starting :active} (:status receipt)) (cancel! host id))
          (await-start! host id)
          (cleanup! host id)
          (when (#{:failed :running :pending} (get-in (receipts host) [id :cleanup]))
            (throw (ex-info "Old run cleanup was not verified" {:id id}))))
        ((requiring-resolve 'propagators.compiler-2.runtime/stop-clocks!) (runtime-session host))
        (doseq [trace (vals (:traces @(runtime-session host)))] ((:stop trace)))
        (locking (gate host)
          (publish! host candidate)
          (swap! (runtime-session host) assoc ::pending [] ::receipts {}))
        (finally
          (locking (gate host) (swap! (runtime-session host) assoc ::phase :idle))))
      (step! host))))

(defn subscribe!
  "Start an external source after the current session has been published.
  With no output cell, its normalizer must produce ordinary cell messages."
  ([host id dependency-id] (subscribe! host id dependency-id nil))
  ([host id dependency-id output-cell]
   (dispatch! host {:boundary/id [:subscription (:run-id (session/session-identity
                                                        (current-session host))) id]
                    :boundary/receipt-id output-cell
                    :boundary/payload {:dependency dependency-id :subscription id
                                       :run-id (:run-id (session/session-identity
                                                        (current-session host)))
                                       :input-ids [] :input-contents [] :arguments []}})))

(defn- close-boundary! [host]
  (locking (gate host)
    (swap! (runtime-session host) assoc ::closed? true ::pending []))
  (.shutdownNow ^ExecutorService (.-executor ^SessionBoundary host))
  (locking (gate host)
    (swap! (runtime-session host) update ::receipts
           (fn [receipts]
             (into {} (map (fn [[id receipt]]
                             [id (if (#{:starting :active} (:status receipt))
                                   (assoc receipt :status :cancelled) receipt)])) receipts))))
  (.shutdown ^ExecutorService (.-deliveries ^SessionBoundary host))
  (when-not (.awaitTermination ^ExecutorService (.-deliveries ^SessionBoundary host)
                              (long (.-cleanupTimeout ^SessionBoundary host))
                              TimeUnit/MILLISECONDS)
    (.shutdownNow ^ExecutorService (.-deliveries ^SessionBoundary host)))
  ((requiring-resolve 'propagators.compiler-2.runtime/stop-clocks!) (runtime-session host))
  (doseq [trace (vals (:traces @(runtime-session host)))] ((:stop trace)))
  (let [errors (reduce (fn [errors [id receipt]]
                         (try
                           (when (#{:active :starting} (:status receipt)) (cancel! host id))
                           (cleanup! host id)
                           (if (#{:failed :running :pending}
                                (get-in (receipts host) [id :cleanup]))
                             (conj errors id) errors)
                           (catch Exception error (conj errors {:id id :error error}))))
                       (if (.isTerminated ^ExecutorService (.-deliveries ^SessionBoundary host))
                         [] [:delivery-still-running])
                       (receipts host))]
    (when (seq errors)
      (throw (ex-info "Session cleanup was not verified" {:errors errors})))
    {:status :closed}))

(defn close!
  "Serialize shutdown with round publication and post-commit observer installation."
  [host]
  (locking (round-gate host)
    (close-boundary! host)))
