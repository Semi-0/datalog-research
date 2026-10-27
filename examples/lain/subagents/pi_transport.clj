(ns examples.lain.subagents.pi-transport
  "Session-owned bounded Pi transport. The injected RPC must never prompt a parent.
  It accepts tool requests and returns normalized observations; it owns Pi children."
  (:require [examples.lain.subagents.model :as model]
            [examples.lain.subagents.boundary :as boundary]))

(def pins {:pi-subagents "0.81.0" :pi-better-subagents "0.19.1"})
(defn validate-config [{:keys [plugin version provider model cwd tools] :as config}]
  (when-not (and (contains? pins plugin) (= version (pins plugin))
                 (every? #(and (string? %) (seq %)) [provider model cwd])
                 (.isAbsolute (java.io.File. cwd))
                 (vector? tools) (every? #(and (string? %) (seq %)) tools)
                 (= (count tools) (count (distinct tools))))
    (throw (ex-info "Pi requires a pinned plugin, explicit provider/model/absolute cwd/tools" {})))
  (select-keys config [:plugin :version :provider :model :cwd :tools]))

(defn- bounded-response [raw]
  (when-not (and (map? raw) (#{:launched :reconciled :completed :stopped :uncertain :failed :running} (:status raw)))
    (throw (ex-info "Unsupported Pi RPC response" {})))
  (let [response (select-keys raw [:status :child :result :failure :cleanup-verified])]
    (when (> (count (pr-str response)) 1048576)
      (throw (ex-info "Pi response exceeds bounded transport limit" {})))
    response))

(defn plugin-request
  "Translate one bounded FSM request; do not serialize a graph or an actor."
  [config request]
  (let [{:keys [plugin provider model cwd tools]} (validate-config config)
        operation (:operation request)
        base (model/plugin-request (assoc request :backend plugin))
        policy {:provider provider :model model :cwd cwd :tools tools}]
    (when (and (= :launch operation)
               (not (and (string? (:task request)) (seq (:task request))
                         (<= (count (:task request)) 65536))))
      (throw (ex-info "Pi launch requires bounded nonempty task text" {})))
    (if (= :launch operation)
      (case plugin
        :pi-subagents (update base :arguments merge policy)
        :pi-better-subagents (update-in base [:arguments :tasks]
                                       #(mapv (fn [task] (merge task policy)) %)))
      base)))

(defn open!
  "RPC hooks are explicit: :call!, :reconcile!, :dispose!. No I/O in observe/replay.
  Reconciliation must find an uncertain launch by stable :effect-id, never relaunch."
  [identity config {:keys [mode call! reconcile! dispose! recording]
                    :or {mode :observe}}]
  (let [config (validate-config config)]
    (when (and (#{:live :synthetic} mode)
               (not (every? fn? [call! reconcile! dispose!])))
      (throw (ex-info "Execution needs call, reconciliation and verified disposal hooks" {})))
    {:identity identity :config config :mode mode :call! call!
     :reconcile! reconcile! :dispose! dispose! :recording recording
     :state (atom {:closed? false :trials {} :polls {} :activated? false})}))

(defn- invoke! [adapter request]
  (let [operation (:operation request)
        context {:session (:identity adapter) :trial (:session request)
                 :effect-id (:id request) :config (:config adapter)
                 :assignment-context (select-keys (:context request) [:instructions :trial-id])}]
    (case operation
      :dispose ((:dispose! adapter) context)
      :reconcile (bounded-response ((:reconcile! adapter)
                                      (assoc context :child (:child request)
                                             :launch-effect-id (model/effect-id (:session request) :launch))))
      (do
        (when (and (= :launch operation)
                   (= :pi-subagents (get-in adapter [:config :plugin]))
                   (not (:activated? @(:state adapter))))
          ((:call! adapter) (assoc context :tool "subagents_enable" :arguments {}))
          (swap! (:state adapter) assoc :activated? true))
        (bounded-response ((:call! adapter) (merge context (plugin-request (:config adapter) request))))))))

(declare poll!)

(defn dispatch!
  "Use existing boundary claim/receipt semantics, one stable boundary per assignment.
  The caller supplies a model request derived from facts. No launch policy lives here."
  [adapter assignment-id request]
  (locking (:state adapter)
    (when (:closed? @(:state adapter))
      (throw (ex-info "Pi adapter is disposed" {})))
    (when-not (and (= (:backend request) (get-in adapter [:config :plugin]))
                   (nat-int? (:order request)))
      (throw (ex-info "Pi request needs selected backend and explicit observation order" {})))
    (when-not (= (:session request) (pr-str [(:identity adapter) assignment-id]))
      (throw (ex-info "Foreign Pi trial" {:assignment-id assignment-id})))
    (let [trial (or (get-in @(:state adapter) [:trials assignment-id])
                    (let [opened (boundary/open! (:session request)
                                   {:mode (:mode adapter) :recording (:recording adapter)
                                    :invoke #(invoke! adapter %)})]
                      (swap! (:state adapter) assoc-in [:trials assignment-id] opened)
                      opened))]
      (if (= :collect (:operation request))
        (poll! adapter assignment-id (pr-str (:id request)) (:order request))
        (boundary/dispatch! trial request)))))

(defn poll!
  "Explicit poll identity permits progress without redispatching a prior poll.
  Polling is separate from one-shot FSM collect receipts. Only acknowledged owned
  children may be polled; the RPC returns a bounded normalized status/result."
  [adapter assignment-id poll-id order]
  (locking (:state adapter)
    (when (:closed? @(:state adapter)) (throw (ex-info "Pi adapter is disposed" {})))
    (when-not (and (string? poll-id) (seq poll-id) (nat-int? order))
      (throw (ex-info "Invalid poll identity/order" {})))
    (if (#{:observe :replay} (:mode adapter))
      []
      (if-let [prior (get-in @(:state adapter) [:polls [assignment-id poll-id]])]
        prior
        (let [trial (get-in @(:state adapter) [:trials assignment-id])
              observations (mapcat :observations (vals (when trial (boundary/receipts trial))))
              child (:child (last (filter :child observations)))]
          (when-not child (throw (ex-info "Polling needs an acknowledged owned child" {})))
          (let [request {:session (:identity trial) :id [(:identity trial) :poll poll-id]
                         :backend (get-in adapter [:config :plugin])
                         :operation :collect :child child :order order}
                _ (swap! (:state adapter) assoc-in [:polls [assignment-id poll-id]] [])
                raw (try (invoke! adapter request)
                         (catch Exception error
                           {:status :failed :failure {:message (.getMessage error)}}))
                result (if (= :running (:status raw))
                         [{:session (:session request) :id (pr-str (:id request))
                           :order order :kind :progress :child child}]
                         [(boundary/normalize-response request raw)])]
            (swap! (:state adapter) assoc-in [:polls [assignment-id poll-id]] result)
            result))))))

(defn receipts [adapter]
  {:trials (into {} (map (fn [[assignment trial]] [assignment (boundary/receipts trial)]))
                 (:trials @(:state adapter)))
   :polls (:polls @(:state adapter))})

(defn close!
  "Dispose the owning RPC once, including uncertain launches. Refuse unverified cleanup."
  [adapter]
  (locking (:state adapter)
    (if (:closed? @(:state adapter))
      {:cleanup-verified true :already-closed true}
      (let [receipt (if (#{:observe :replay} (:mode adapter))
                      {:cleanup-verified true}
                      ((:dispose! adapter) {:session (:identity adapter)
                                            :config (:config adapter)}))]
        (when-not (true? (:cleanup-verified receipt))
          (throw (ex-info "Pi disposal did not verify child cleanup" {:receipt receipt})))
        (swap! (:state adapter) assoc :closed? true)
        receipt))))
