(ns examples.lain.subagents.model
  "Pure ordered event facts and the single subagent transition policy.")

(def backends #{:pi-subagents :pi-better-subagents})
(def event-kinds #{:start :launched :completed :cancel :stopped :uncertain :reconciled :failed})
(defrecord EventLog [session facts])
(defrecord State [phase backend task child result failure])

(defn event [session id order kind fields]
  (when-not (and (string? session) (seq session) (string? id) (seq id)
                 (integer? order) (not (neg? order)) (event-kinds kind) (map? fields))
    (throw (ex-info "Invalid subagent event" {:session session :id id :order order :kind kind})))
  (when (some #(contains? fields %) [:session :id :order :kind])
    (throw (ex-info "Event fields cannot override identity" {:fields fields})))
  (merge fields {:session session :id id :order order :kind kind}))
(defn empty-events [session]
  (when-not (and (string? session) (seq session))
    (throw (ex-info "Session identity must be a nonempty string" {})))
  (->EventLog session {}))
(defn session-id [log] (:session log))
(defn ordered-events [log] (sort-by (juxt :order :id) (vals (:facts log))))
(defn accumulate
  "Union immutable facts. Conflicting identities and foreign observations fail closed."
  [log incoming]
  (reduce
   (fn [current candidate]
     (let [{:keys [session id order kind]} candidate
           validated (event session id order kind (dissoc candidate :session :id :order :kind))]
       (when-not (= (session-id current) session)
         (throw (ex-info "Foreign session event" {:expected (session-id current) :actual session})))
       (if-let [prior (get (:facts current) id)]
         (if (= prior validated) current (throw (ex-info "Conflicting event identity" {:id id})))
         (assoc-in current [:facts id] validated))))
   log incoming))
(defn merge-events [left right]
  (when-not (= (session-id left) (session-id right)) (throw (ex-info "Cannot merge sessions" {})))
  (accumulate left (ordered-events right)))
(defn initial-state [] (->State :idle nil nil nil nil nil))
(defn phase [state] (:phase state))
(defn backend [state] (:backend state))
(defn task [state] (:task state))
(defn child [state] (:child state))
(defn result [state] (:result state))
(defn failure [state] (:failure state))
(defn effect-id [session operation] [session operation])
(defn- require-child [state observation]
  (when-not (and (string? (:child observation)) (seq (:child observation)))
    (throw (ex-info "Observation needs a child identity" {:event (:id observation)})))
  (when (and (child state) (not= (child state) (:child observation)))
    (throw (ex-info "Observation does not belong to the owned child" {:event (:id observation)}))))
(defn transition
  "Replay one fact. Command ordering is explicit; terminal states absorb late observations."
  [state observation]
  (let [kind (:kind observation) current (phase state)]
    (case kind
      :start (do
               (when-not (and (backends (:backend observation))
                              (string? (:task observation)) (seq (:task observation)))
                 (throw (ex-info "Start needs a pinned backend and task" {:event (:id observation)})))
               (if (= :idle current)
                 (->State :launching (:backend observation) (:task observation) nil nil nil)
                 (throw (ex-info "A trial accepts one start command" {:event (:id observation)}))))
      :cancel (if (#{:launching :running :uncertain} current) (assoc state :phase :cancelling) state)
      :uncertain (if (= :launching current) (assoc state :phase :uncertain) state)
      :launched (do (require-child state observation)
                    (if (#{:launching :uncertain} current)
                      (assoc state :phase :running :child (:child observation))
                      (if (= :cancelling current) (assoc state :child (:child observation)) state)))
      :reconciled (do (require-child state observation)
                      (if (#{:uncertain :cancelling} current)
                        (assoc state :child (:child observation)
                               :phase (if (= :cancelling current) :cancelling :running)) state))
      :completed (do (require-child state observation)
                     (if (#{:running :launching :uncertain} current)
                       (assoc state :phase :completed :child (:child observation) :result (:result observation)) state))
      :stopped (do (require-child state observation)
                   (when-not (true? (:cleanup-verified observation))
                     (throw (ex-info "Stop must verify child cleanup" {:event (:id observation)})))
                   (if (= :cancelling current) (assoc state :phase :cancelled :child (:child observation)) state))
      :failed (if (#{:completed :cancelled} current) state (assoc state :phase :failed :failure (:failure observation))))))
(defn current-state [events] (reduce transition (initial-state) (ordered-events events)))
(defn operation [state]
  (case (phase state)
    :launching :launch
    :uncertain :reconcile
    :running :collect
    :cancelling (if (child state) :stop :reconcile)
    (:idle :completed :cancelled :failed) :none))
(defn request [session state]
  (let [op (operation state)]
    {:session session :id (effect-id session op) :operation op
     :backend (backend state) :task (task state) :child (child state)}))
(defn plugin-request [{:keys [backend operation task child]}]
  (when-not (backends backend) (throw (ex-info "Unsupported backend" {:backend backend})))
  (case backend
    :pi-subagents
    (case operation
      :launch {:tool "subagent" :arguments {:agent "lain-worker" :task task :async true}}
      :reconcile {:tool "subagent" :arguments {:action "status" :id child}}
      :collect {:tool "subagent" :arguments {:action "status" :id child}}
      :stop {:tool "subagent" :arguments {:action "stop" :id child}})
    :pi-better-subagents
    (case operation
      :launch {:tool "subagent_spawn_batch" :arguments {:tasks [{:agent "lain-worker" :task task}]}}
      :reconcile {:tool "subagent_result" :arguments {:id child}}
      :collect {:tool "subagent_result" :arguments {:id child}}
      :stop {:tool "subagent_stop" :arguments {:id child}})))
