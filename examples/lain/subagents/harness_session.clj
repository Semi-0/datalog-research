(ns examples.lain.subagents.harness-session
  "One owned session extension and outside effect pump. Lain derives requests;
  this module claims eligible intents, invokes interpreters, and returns facts."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [examples.lain.subagents.effect-boundary :as effects]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.harbor-extension :as harbor]
            [examples.lain.subagents.pi-extension :as pi]
            [examples.lain.subagents.pi-transport :as pi-transport]
            [examples.lain.subagents.primitives :as primitives]
            [examples.lain.subagents.socket-extension :as socket]
            [examples.lain.subagents.socket-transport :as socket-transport]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.boundary.effects :as boundary-effects]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
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
   :pi-intents 'harness:pi-intents :harbor-intents 'harness:harbor-intents
   :socket-intents 'harness:socket-intents
   :assignments 'harness:assignments :controls 'harness:controls
   :offer 'harness:offer :results 'harness:results :feedback 'harness:feedback})

(def source
  "(load-lain \"examples/lain/subagents/composition.lain\")
   (define harness:socket-in)
   (-> harness:socket-in (socket:observations socket))
   (define harness:pi-in)
   (-> harness:pi-in (pi:observations pi))
   (define harness:harbor-in)
   (-> harness:harbor-in (harbor:observations harbor))
   (define harness:assignments (assignments evaluation-framework))
   (define harness:controls (runtime:controls runtime))
   (define harness:offer (on-going-assignment evaluation-framework))
   (define harness:results (assignment-result runtime))
   (define harness:feedback (user:feedback user))
   ;; Domain eligibility stays in the network: each boundary publishes only its
   ;; CURRENT intents as a retractable value, and declares one native effect slot
   ;; per distinct intent. The RPC pump reads that state; it owns no policy.
   (define harness:pi-intents (pi:current-requests harness:offer harness:controls harness:pi-in))
   (define harness:pi-out (pi:emit-requests harness:pi-intents))
   (define harness:harbor-intents (harbor:current-requests harness:assignments harness:results harness:harbor-in))
   (define harness:harbor-out (harbor:emit-requests harness:harbor-intents))
   (define harness:socket-intents (socket:current-writes harness:feedback))
   (define harness:socket-out (socket:emit-writes harness:socket-intents))")

;; ---------------------------------------------------------------- declaration loading
;; `load-lain` resolves its argument as a FILE relative to the process working directory
;; (environment_io.clj:32,151), so a session declared with a relative directive silently installs
;; inlet cells with no composition behind them whenever the JVM starts anywhere other than the
;; project root. The harness locates its own declaration on the classpath and resolves every nested
;; directive against that root, so the graph never depends on how the process was launched.
(def composition-resource "examples/lain/subagents/composition.lain")
(def max-declaration-depth 32)

(defn declaration-root
  "Absolute project root owning the declaration, taken from the classpath location of
   composition-resource; nil when that resource is not a readable file." []
  (when-let [url (io/resource composition-resource)]
    (when (= "file" (.getProtocol url))
      (let [path (.getPath url)]
        (when (str/ends-with? path composition-resource)
          (io/file (subs path 0 (- (count path) (count composition-resource)))))))))

(defn- load-directive-path
  [form]
  (when (and (seq? form) (= 'load-lain (first form)) (string? (second form)))
    (second form)))

(def path-directives
  "Directives whose first argument is a file path. The runtime canonicalizes them against the
   process working directory, so the declaration rewrites them to absolute before compiling."
  #{'load-primitive-environment 'load-blocks})

(defn- absolute-directive
  [root form]
  (if (and (seq? form) (path-directives (first form)) (string? (second form)))
    (let [candidate (io/file (second form))
          target (if (.isAbsolute candidate) candidate (io/file root (second form)))]
      (when-not (.canRead target)
        (throw (ex-info "Harness declaration file is not readable"
                        {:file (.getAbsolutePath (.getCanonicalFile target))
                         :declared-as (second form)
                         :declaration-root root
                         :directive (first form)
                         :working-directory (System/getProperty "user.dir")})))
      (cons (first form) (cons (.getAbsolutePath (.getCanonicalFile target)) (drop 1 (rest form)))))
    form))

(defn declaration-source
  "text with every (load-lain \"path\") directive replaced, in place and once per file, by the forms
   of the file it names, resolved against the declaration root instead of the process working
   directory. Nested directives resolve the same way, so a foreign cwd cannot silently drop the
   composition. With no resolvable root the text is returned untouched and the runtime load failure
   surfaces through declaration-failures." ([] (declaration-source source))
  ([text]
   (if-not (declaration-root)
     text
     (let [seen (atom #{})]
       (str/join "\n"
                 (map pr-str
                      (letfn [(expand [text stack depth]
                                (when (> depth max-declaration-depth)
                                  (throw (ex-info "Maximum .lain nesting depth exceeded"
                                                  {:depth depth :load-stack stack})))
                                (mapcat
                                  (fn [form]
                                    (if-let [file (load-directive-path form)]
                                      (let [target (let [candidate (io/file file)]
                                                     (if (.isAbsolute candidate)
                                                       candidate
                                                       (io/file (declaration-root) file)))
                                            path (.getAbsolutePath (.getCanonicalFile target))]
                                        (when-not (.canRead target)
                                          (throw (ex-info "Harness declaration file is not readable"
                                                          {:file path
                                                           :declared-as file
                                                           :declaration-root (declaration-root)
                                                           :working-directory (System/getProperty "user.dir")})))
                                        (when (some #{path} stack)
                                          (throw (ex-info "cyclic .lain load"
                                                          {:path path :load-stack stack})))
                                        (if (contains? @seen path)
                                          []
                                          (do (swap! seen conj path)
                                              (expand (slurp target) (conj stack path) (inc depth)))))
                                      [(absolute-directive (declaration-root) form)]))
                                  (loader/source-forms text)))]
                        (expand text [] 0))))))))

(defn declaration-failures
  "Declaration-time load failures: the runtime's own error plus every FAILED environment effect
   delivery, because nested load-lain receipts live there and nowhere in :load-result. Pure." [load-result state]
  (into (when-let [error (:error load-result)]
          [{:kind :declaration :error (dissoc error :state)}])
        (keep (fn [[effect-id delivery]]
                (when (= :failed (:status delivery))
                  {:kind :environment-effect :effect-id effect-id :receipt (:receipt delivery)})))
              (filter (comp #{"environment"} namespace first key)
                      (:environment/effects state))))

(defn- load-declaration
  "New session, extensions and declaration, keeping the value load-session-from-source discards:
   file_loader.clj:84-90 drops load-source!'s return, so [:load-result :error] can never be read
   back out of session state." [identity bundle text extensions]
  (let [session (runtime/new-session)]
    (doseq [[extension config] extensions]
      (runtime/install-session-extension! session extension config))
    (binding [runner/*advance-transform*
              (runner/compose-advance-transforms runner/*advance-transform*
                                                 (primitives/runner-transform identity))]
      (runtime/install-session-extension! session bundle
                                         {:outbox-id (runtime-ids/boundary-outbox-id)})
      (loader/load-source! session text))))

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

(def effect-routes
  "Native effect registrations this session delivers. Handlers are resolved from
  THIS session's extension registry (qualified symbols owned by the component),
  never from a global table. There is no host-side eligibility rule here: the
  graph already decided what is current."
  {:harness/pi-request {:boundary :pi :current :pi-intents}
   :harness/harbor-request {:boundary :harbor :current :harbor-intents}
   :harness/socket-write {:boundary :socket :current :socket-intents}})


(defn open!
  "Observation mode is default. Execution requires explicit Pi and Harbor hooks.
  A live socket is opt-in via :socket-options {:mode :live}; it stays loopback-only.
  Optional :extensions contains [bundle config] pairs installed before compiling the declaration."
  [identity {:keys [mode pi-config pi-hooks harbor-callbacks socket-options
                    socket-invoke! recording-paths extensions]
             :or {mode :observe socket-options {}}}]
  (when-not (#{:observe :synthetic :live :replay} mode)
    (throw (ex-info "Unsupported harness mode" {:mode mode})))
  (when (and (#{:synthetic :live} mode)
             (not (every? ifn? (map harbor-callbacks [:prepare :grade :dispose!]))))
    (throw (ex-info "Execution requires admitted Harbor preparation, grading and disposal" {})))
  (when (and (#{:observe :replay} mode) (= :live (:mode socket-options)))
    (throw (ex-info "Observation/replay cannot open a live socket" {})))
  (let [bundle (session-extension identity pi-config)
        {:keys [session load-result]} (load-declaration identity bundle (declaration-source) extensions)
        _ (when-let [failures (seq (declaration-failures load-result @session))]
            (throw (ex-info "Harness declaration failed to load"
                            (merge {:failures failures
                                    :working-directory (System/getProperty "user.dir")
                                    :declaration-root (declaration-root)}
                                   (when-let [error (:error load-result)] {:error error})))))
        state @session
        ;; Fail at open instead of silently stalling: pending-effects drops any request whose
        ;; registration is missing (the W22 zero-emissions shape), so require every routed intent
        ;; kind to have a registration in THIS session's handler table now.
        _ (doseq [wanted (keys effect-routes)]
            (when-not (some (fn [[[_ kind]]] (= kind wanted))
                            (:environment/handlers state))
              (throw (ex-info "Harness effect handler was not registered"
                              {:kind wanted
                               :registered (vec (keys (:environment/handlers state)))}))))
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
                                           ;; A non-live socket performs no I/O: acknowledge the
                                           ;; write without transport, exactly like pi-transport
                                           ;; poll! returns [] in :observe/:replay. send! would
                                           ;; throw, poison the ledger with an :uncertain receipt
                                           ;; and permanently disable the delivery pump.
                                           {:observations []
                                            :receipt {:writes (if (= :live (:mode socket-owner))
                                                                (socket-transport/send! socket-owner
                                                                                      (:payload request))
                                                                0)}})))})
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

(defn- pi-receipted? [harness intent]
  (let [request (:request intent) assignment (:assignment-id intent)
        receipts (pi-transport/receipts (:pi harness))]
    (if (= :collect (:operation request))
      (contains? (:polls receipts) [assignment (pr-str (:id request))])
      (contains? (get-in receipts [:trials assignment]) (:id request)))))

(defn- current-intents [harness port]
  (let [candidate (raw-port harness port)]
    (if (value/nothing? candidate) [] (vec candidate))))

(defn- current-pi-intents [harness]
  (into {} (map (juxt :id identity)) (current-intents harness :pi-intents)))

(defn- current-effect? [harness spec request]
  ;; Exact intent equality: a canceled or reordered revision is not current.
  (let [intent (:boundary/payload request)]
    (boolean (some #(= % intent) (current-intents harness (:current spec))))))

(defn- receipted? [harness boundary intent]
  (case boundary
    :harbor (contains? (effects/receipts (:harbor harness)) (:id intent))
    :socket (contains? (effects/receipts (:writes harness)) (:id intent))
    :pi (pi-receipted? harness intent)))

(defn- pending-effects
  "Drain the graph's intent and receipt state. Ordering is deterministic effect
  identity only: every current, unreceipted intent is delivered in this round, so
  concurrent Pi, Harbor and socket intents are never serialized by a chooser."
  [harness]
  (let [handlers (:environment/handlers @(:session harness))]
    (into []
          (keep (fn [request]
                  (when-let [spec (get effect-routes (:boundary/kind request))]
                    (when-let [registration (get handlers [(:boundary/port request)
                                                           (:boundary/kind request)])]
                    (let [handler (:handler registration)]
                      (when (satisfies? extension/EffectHandler handler)
                        (let [intent (:boundary/payload request)]
                          (when (and (current-effect? harness spec request)
                                     (not (receipted? harness (:boundary spec) intent)))
                            (assoc request ::registration handler)))))))))
          (sort-by (comp pr-str :boundary/id)
                   (boundary-effects/outbox-effects (:program/net @(:session harness)))))))

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

(defn- delivered-observations
  "Observations returned by a component handler through the native handler contract:
  {:state <session state> :receipt {:status ... :observations <facts>}}."
  [result]
  (get-in result [:receipt :observations]))

(defn- delivery-services [harness]
  {:pi (:pi harness) :harbor (:harbor harness) :writes (:writes harness)
   :order (take-order! harness) :harness harness})

(defn- dispatch-effect! [harness request]
  ;; Transport is armed by pump ownership alone: open!, observe!, replay and any
  ;; runtime-side drain seam perform no external transport.
  (when-not (primitives/transport-armed?)
    (throw (ex-info "External effect transport is pump-owned; this path performs no transport"
                    {:boundary (:boundary/port request) :effect-id (:boundary/id request)})))
  (let [spec (get effect-routes (:boundary/kind request))
        boundary (:boundary spec)
        intent (:boundary/payload request)]
    ;; Revalidate currency immediately before the claim: a reorder/cancel that
    ;; landed after emission rejects the historical intent (no relaunch).
    (when (current-effect? harness spec request)
      (case boundary
        :harbor (let [observations (delivered-observations
                                    (extension/handle-effect (::registration request)
                                                             (delivery-services harness)
                                                             @(:session harness) request))]
                  (require-successful-effects! harness)
                  (observe! harness :harbor observations))
        :socket (extension/handle-effect (::registration request)
                                         (delivery-services harness)
                                         @(:session harness) request)
        (let [assignment (:assignment-id intent) request' (:request intent)
              operation (:operation request')]
          ;; The claim becomes a fact before transport invocation, including lost acks.
          (when (= :launch operation)
            (observe! harness :pi
              (facts/from-facts (:identity harness)
                                [(pi/requested-fact (:identity harness) assignment (take-order! harness) request')])))
          (when (= :stop operation)
            (observe! harness :pi
              (facts/from-facts (:identity harness)
                                [(pi/cancel-requested-fact (:identity harness) assignment (take-order! harness) request')])))
          (publish-pi-observations!
           harness assignment
           (delivered-observations
            (extension/handle-effect (::registration request)
                                     (delivery-services harness)
                                     @(:session harness) request))))))))

(defn pump!
  "Drain declared effect intents to a bound; this does not interpret workflow
  phases or recompute any domain policy. Every current, unreceipted intent is
  delivered in the round it is declared (no one-per-tick chooser); stable effect
  identity suppresses redispatch and currency revalidation rejects intents a
  reorder/cancel made historical before their claim. Transport is armed here only."
  ([harness] (pump! harness 128))
  ([harness max-steps]
   (locking (:lock harness)
     (require-open! harness)
     (when-not (pos-int? max-steps) (throw (ex-info "Positive pump bound required" {})))
     (if (#{:observe :replay} (:mode harness))
       {:status :observation-only :steps 0}
       (primitives/with-transport-ownership
         #(loop [steps 0]
            (require-successful-effects! harness)
            (let [effects (pending-effects harness)]
              (if (empty? effects)
                {:status :quiescent :steps steps}
                (if (> (+ steps (count effects)) max-steps)
                  (throw (ex-info "Harness effect pump bound reached"
                                  {:steps steps :pending (count effects)}))
                  (do (run! (partial dispatch-effect! harness) effects)
                      (recur (+ steps (count effects)))))))))))))

(defn poll!
  "One explicitly identified polling pulse. The caller owns interval and retention."
  [harness poll-id]
  (locking (:lock harness)
    (require-open! harness)
    (primitives/with-transport-ownership
      #(do (when-not (#{:observe :replay} (:mode harness))
        (doseq [intent (current-intents harness :pi-intents)
                :when (= :collect (get-in intent [:request :operation]))]
          (publish-pi-observations!
           harness (:assignment-id intent)
           (pi-transport/poll! (:pi harness) (:assignment-id intent) poll-id (take-order! harness)))))
         (pump! harness)))))

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
