(ns examples.lain.subagents.harness-session
  "One owned session extension and outside effect pump. Lain derives requests;
  this module claims eligible intents, invokes interpreters, and returns facts."
  (:require [examples.lain.subagents.effect-boundary :as effects]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.harbor-extension :as harbor]
            [examples.lain.subagents.pi-extension :as pi]
            [examples.lain.subagents.pi-transport :as pi-transport]
            [examples.lain.subagents.primitives :as primitives]
            [examples.lain.subagents.socket-extension :as socket]
            [examples.lain.subagents.socket-transport :as socket-transport]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.runner :as runner]))

(defrecord Harness [identity mode config session ports lock clock closed socket pi harbor writes])

(def port-bindings
  {:socket-in 'harness:socket-in :socket-out 'harness:socket-out
   :pi-in 'harness:pi-in :pi-out 'harness:pi-out
   :harbor-in 'harness:harbor-in :harbor-out 'harness:harbor-out
   :assignments 'harness:assignments :controls 'harness:controls
   :offer 'harness:offer :results 'harness:results})

(def source
  "(load-lain \"examples/lain/subagents/composition.lain\")
   (define harness:socket-in)
   (-> harness:socket-in (socket:observations socket))
   (define harness:socket-out (socket:requests socket))
   (define harness:pi-in)
   (-> harness:pi-in (pi:observations pi))
   (define harness:pi-out (pi:requests pi))
   (define harness:harbor-in)
   (-> harness:harbor-in (harbor:observations harbor))
   (define harness:harbor-out (harbor:requests harbor))
   (define harness:assignments (assignments evaluation-framework))
   (define harness:controls (runtime:controls runtime))
   (define harness:offer (on-going-assignment evaluation-framework))
   (define harness:results (assignment-result runtime))")

(defn session-extension [identity config]
  (let [parts [(socket/session-extension identity)
               (harbor/session-extension identity)
               (pi/session-extension identity config)]]
    (extension/extension-bundle
     {:id ::harness
      :bindings (into [] (mapcat extension/extension-bindings) parts)
      :effects (into [] (mapcat extension/extension-effects) parts)})))

(defn- raw-port [harness port]
  (when-not (contains? (:ports harness) port)
    (throw (ex-info "Unknown harness port" {:port port})))
  (data/payload (net/network-cell-strongest
                 (:program/net @(:session harness)) (get (:ports harness) port))))

(defn facts-at [harness port]
  (let [candidate (raw-port harness port)]
    (if (value/nothing? candidate)
      (facts/empty-log (:identity harness))
      (do
        (when-not (and (facts/log? candidate)
                       (= (:identity harness) (facts/session-id candidate)))
          (throw (ex-info "Harness port does not expose owned facts" {:port port})))
        candidate))))

(defn current-offer [harness]
  (let [candidate (raw-port harness :offer)]
    (when-not (value/nothing? candidate) candidate)))

(defn- require-open! [harness]
  (when @(:closed harness) (throw (ex-info "Harness session is closed" {}))))

(defn observe!
  "Publish external facts only through the owning inlet. No FSM state is copied."
  [harness channel log]
  (locking (:lock harness)
    (require-open! harness)
    (let [port (case channel :socket :socket-in :pi :pi-in :harbor :harbor-in
                     (throw (ex-info "Unknown observation channel" {:channel channel})))]
      (primitives/publish-facts! (:session harness) (:identity harness)
                                 (get (:ports harness) port) log)
      (when-let [last-event (last (facts/ordered-facts log))]
        (swap! (:clock harness) max (inc (:order last-event))))
      harness)))

(defn- take-order! [harness]
  (let [order @(:clock harness)] (swap! (:clock harness) inc) order))

(defn- harbor-invoke [callbacks request]
  (if (= :dispose (:operation request))
    (let [dispose (:dispose! callbacks)]
      (when-not (ifn? dispose)
        (throw (ex-info "Harbor execution requires an explicit disposer" {})))
      {:observations [] :receipt (dispose (:session request))})
    (harbor/interpret! callbacks request)))

(defn open!
  "Observation mode is default. Execution requires explicit Pi and Harbor hooks.
  A live socket is opt-in via :socket-options {:mode :live}; it stays loopback-only."
  [identity {:keys [mode pi-config pi-hooks harbor-callbacks socket-options
                    socket-invoke! recording-paths]
             :or {mode :observe socket-options {}}}]
  (when-not (#{:observe :synthetic :live :replay} mode)
    (throw (ex-info "Unsupported harness mode" {:mode mode})))
  (when (and (#{:synthetic :live} mode)
             (not (every? ifn? (map harbor-callbacks [:prepare :grade :dispose!]))))
    (throw (ex-info "Execution requires admitted Harbor preparation, grading and disposal" {})))
  (when (and (#{:observe :replay} mode) (= :live (:mode socket-options)))
    (throw (ex-info "Observation/replay cannot open a live socket" {})))
  (let [bundle (session-extension identity pi-config)
        session (binding [runner/*advance-transform*
                          (runner/compose-advance-transforms runner/*advance-transform*
                                                              (primitives/runner-transform identity))]
                  (loader/load-session-from-source source {:extensions [bundle]}))
        _ (when-let [error (get-in @session [:load-result :error])]
            (throw (ex-info "Harness declaration failed to load" {:error error})))
        state @session
        ports (into {} (map (fn [[key symbol]]
                              [key (or (env/resolve-binding-id (:program/net state)
                                                               (:program/env state) symbol)
                                       (throw (ex-info "Harness port was not declared" {:port key})))]))
                    port-bindings)
        owner (atom nil)
        resources (atom [])]
    (try
      (let [pi-owner (pi-transport/open! identity pi-config (assoc pi-hooks :mode mode))
            _ (swap! resources conj #(pi-transport/close! pi-owner))
            harbor-owner (effects/open! identity {:mode mode
                                                   :invoke! (partial harbor-invoke harbor-callbacks)
                                                   :recording (:harbor recording-paths)})
            _ (swap! resources conj #(effects/close! harbor-owner))
            socket-owner (socket-transport/start!
                          identity
                          (merge {:mode (if (= :replay mode) :replay :observe)} socket-options
                                 {:on-observation #(observe! @owner :socket %)}))
            _ (swap! resources conj #(socket-transport/close! socket-owner))
            write-owner (effects/open!
                         identity
                         {:mode mode :recording (:socket recording-paths)
                          :invoke! (or socket-invoke!
                                       (fn [request]
                                         (if (= :dispose (:operation request))
                                           {:observations [] :receipt {:cleanup-verified true}}
                                           {:observations []
                                            :receipt {:writes (socket-transport/send! socket-owner (:payload request))}})))})
            _ (swap! resources conj #(effects/close! write-owner))
            harness (->Harness identity mode pi-config session ports (Object.) (atom 0)
                               (atom false) socket-owner pi-owner harbor-owner write-owner)]
        (reset! owner harness)
        harness)
      (catch Throwable error
        (doseq [close-resource (reverse @resources)]
          (try (close-resource) (catch Throwable _ nil)))
        (throw error)))))

(defn socket-port [harness] (socket-transport/port (:socket harness)))

(defn- fresh-pi-requests [harness]
  (let [observations (facts-at harness :pi-in)
        controls (facts-at harness :controls)]
    (facts/merge-logs
     (pi/derive-execution-requests (:identity harness) (:config harness)
                                   (current-offer harness) observations)
     (pi/derive-control-requests (:identity harness) (:config harness) controls observations))))

(defn- pi-receipted? [harness intent]
  (let [request (:request intent) assignment (:assignment-id intent)
        receipts (pi-transport/receipts (:pi harness))]
    (if (= :collect (:operation request))
      (contains? (:polls receipts) [assignment (pr-str (:id request))])
      (contains? (get-in receipts [:trials assignment]) (:id request)))))

(defn- pending-action [harness]
  (let [current (into {} (map (juxt :id identity)) (facts/ordered-facts (fresh-pi-requests harness)))
        pi-actions (for [intent (facts/ordered-facts (facts-at harness :pi-out))
                         :when (and (= intent (get current (:id intent)))
                                    (not (pi-receipted? harness intent)))]
                     {:boundary :pi :intent intent
                      :priority (if (#{:stop :reconcile} (get-in intent [:request :operation])) 0 2)})
        harbor-actions (for [intent (facts/ordered-facts (facts-at harness :harbor-out))
                             :when (and (not (contains? (effects/receipts (:harbor harness)) (:id intent)))
                                        (harbor/request-eligible?
                                         intent
                                         (facts/merge-logs (facts-at harness :assignments)
                                                           (facts-at harness :harbor-in))))]
                         {:boundary :harbor :intent intent :priority 1})
        write-actions (for [intent (facts/ordered-facts (facts-at harness :socket-out))
                            :when (not (contains? (effects/receipts (:writes harness)) (:id intent)))]
                        {:boundary :socket :intent intent :priority 3})]
    (first (sort-by (juxt :priority #(get-in % [:intent :id]))
                    (concat pi-actions harbor-actions write-actions)))))

(defn- publish-pi-observations! [harness assignment observations]
  (observe! harness :pi (facts/from-facts (:identity harness)
                                         (map #(pi/observation-fact (:identity harness) assignment %) observations))))

(defn- require-successful-effects! [harness]
  (let [failures (into []
                       (mapcat (fn [[boundary owner]]
                                 (keep (fn [[id receipt]]
                                         (when (effects/failure-statuses (:status receipt))
                                           {:boundary boundary :request-id id :status (:status receipt)
                                            :error (:error receipt) :receipt (:receipt receipt)}))
                                       (effects/receipts owner))))
                       [[:harbor (:harbor harness)] [:socket (:writes harness)]])]
    (when (seq failures)
      (throw (ex-info "Harness external effect failed; receipts retained" {:failures failures})))))

(defn- dispatch-action! [harness {:keys [boundary intent]}]
  (case boundary
    :harbor (let [observations (effects/dispatch! (:harbor harness) intent (take-order! harness))]
              (require-successful-effects! harness)
              (observe! harness :harbor observations))
    :socket (effects/dispatch! (:writes harness) intent (take-order! harness))
    :pi
    (let [assignment (:assignment-id intent) request (:request intent)
          operation (:operation request)]
      ;; The claim becomes a fact before transport invocation, including lost acks.
      (when (= :launch operation)
        (observe! harness :pi
          (facts/from-facts (:identity harness)
                           [(pi/requested-fact (:identity harness) assignment (take-order! harness) request)])))
      (when (= :stop operation)
        (observe! harness :pi
          (facts/from-facts (:identity harness)
                           [(pi/cancel-requested-fact (:identity harness) assignment (take-order! harness) request)])))
      (publish-pi-observations!
       harness assignment
       (pi-transport/dispatch! (:pi harness) assignment (assoc request :order (take-order! harness)))))))

(defn pump!
  "Drain eligible effect intents to a bound; this does not interpret workflow phases.
  Receipt identity suppresses repeated dispatch; current-policy revalidation rejects
  historical offers made ineligible by a reorder/cancel before their launch claim."
  ([harness] (pump! harness 128))
  ([harness max-steps]
   (locking (:lock harness)
     (require-open! harness)
     (when-not (pos-int? max-steps) (throw (ex-info "Positive pump bound required" {})))
     (if (#{:observe :replay} (:mode harness))
       {:status :observation-only :steps 0}
       (loop [steps 0]
         (require-successful-effects! harness)
         (if-let [action (pending-action harness)]
           (if (= steps max-steps)
             (throw (ex-info "Harness effect pump bound reached" {:steps steps}))
             (do (dispatch-action! harness action) (recur (inc steps))))
           {:status :quiescent :steps steps}))))))

(defn poll!
  "One explicitly identified polling pulse. The caller owns interval and retention."
  [harness poll-id]
  (locking (:lock harness)
    (require-open! harness)
    (when-not (#{:observe :replay} (:mode harness))
      (doseq [intent (facts/ordered-facts (fresh-pi-requests harness))
              :when (= :collect (get-in intent [:request :operation]))]
        (publish-pi-observations!
         harness (:assignment-id intent)
         (pi-transport/poll! (:pi harness) (:assignment-id intent) poll-id (take-order! harness)))))
    (pump! harness)))

(defn- receipt-entries [receipts]
  (mapv (fn [[id receipt]]
          (assoc (select-keys receipt [:status :request :observations :next-order :receipt :error])
                 :effect-id id))
        (sort-by (comp pr-str key) receipts)))

(defn artifacts
  "Project bounded plain execution evidence. No actor, graph, FactLog record or
  map keyed by effect vectors crosses this serialization boundary."
  [harness]
  (let [pi-receipts (pi-transport/receipts (:pi harness))]
    (effects/bounded-data!
     {:session (:identity harness) :mode (:mode harness) :next-order @(:clock harness)
      :configuration (select-keys (:config harness) [:plugin :version :provider :model :cwd :tools])
      :closed? (boolean @(:closed harness))
      :close-result (get-in @(:closed harness) [:outcome :result])
      :socket (socket-transport/snapshot (:socket harness))
      :facts (into {} (map (fn [port] [port (facts/ordered-facts (facts-at harness port))]))
                   [:socket-in :pi-in :harbor-in :assignments :results])
      :harbor-receipts (receipt-entries (effects/receipts (:harbor harness)))
      :socket-receipts (receipt-entries (effects/receipts (:writes harness)))
      :pi-receipts
      {:trials (mapv (fn [[assignment receipts]]
                       {:assignment-id assignment :receipts (receipt-entries receipts)})
                     (sort-by key (:trials pi-receipts)))
       :polls (mapv (fn [[[assignment poll] observations]]
                      {:assignment-id assignment :poll-id poll :observations observations})
                    (sort-by (comp pr-str key) (:polls pi-receipts)))}})))

(defn- claim-close! [harness]
  (locking (:lock harness)
    (if-let [existing @(:closed harness)]
      {:completion (:completion existing)}
      (let [completion (promise)]
        (reset! (:closed harness) {:completion completion})
        {:completion completion :execute? true}))))

(defn- close-resources! [harness]
  (mapv (fn [[component close-resource]]
          (try {:component component :status :closed :receipt (close-resource)}
               (catch Throwable error
                 {:component component :status :failed :failure (ex-message error)})))
        [[:pi #(pi-transport/close! (:pi harness))]
         [:harbor #(effects/close! (:harbor harness))]
         [:socket-writes #(effects/close! (:writes harness))]
         [:socket #(socket-transport/close! (:socket harness))]]))

(defn close!
  "Claim disposal under the session lock, then join outside it. Late ingress
  fails closed; every caller receives the same cached success or cleanup error."
  [harness]
  (let [{:keys [completion execute?]} (claim-close! harness)]
    (when execute?
      (let [results (close-resources! harness)
            failed? (some #(= :failed (:status %)) results)
            result {:status (if failed? :failed :closed) :components results}
            outcome (cond-> {:result result}
                      failed? (assoc :error (ex-info "Harness cleanup was not verified" {:results results})))]
        (reset! (:closed harness) {:completion completion :outcome outcome})
        (deliver completion outcome)))
    (let [{:keys [result error]} @completion]
      (when error (throw error))
      result)))
