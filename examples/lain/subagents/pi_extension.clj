(ns examples.lain.subagents.pi-extension
  "Pure Pi policy leaves. Ordered facts feed the existing per-assignment FSM;
  outside dispatch and transport ownership remain in pi-transport."
  (:require [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.model :as model]
            [examples.lain.subagents.pi-transport :as transport]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [examples.lain.subagents.primitives :as primitives]))

(def runtime-kinds #{:runtime/requested :runtime/started :runtime/uncertain
                     :runtime/completed :runtime/cancelled :runtime/failed
                     :runtime/progress :runtime/reconciled :runtime/cancel-requested})
(def terminal-kinds #{:runtime/completed :runtime/cancelled :runtime/failed})
(defn trial-id [session assignment-id] (pr-str [session assignment-id]))
(defn- checked-log [session log]
  (when-not (and (facts/log? log) (= session (facts/session-id log)))
    (throw (ex-info "Foreign or invalid Pi fact log" {:expected session})))
  log)
(defn normalize-observations [session observations]
  (let [log (checked-log session observations)]
    (doseq [event (facts/ordered-facts log)]
      (when-not (and (runtime-kinds (:kind event))
                     (string? (:assignment-id event)) (seq (:assignment-id event)))
        (throw (ex-info "Invalid Pi observation" {:id (:id event) :kind (:kind event)})))
      (when (> (count (pr-str event)) 1048576)
        (throw (ex-info "Pi observation exceeds bounded fact limit" {:id (:id event)})))
      (when (and (= :runtime/requested (:kind event))
                 (not (and (model/backends (:backend event))
                           (string? (:task event)) (seq (:task event))
                           (<= (count (:task event)) 65536)
                           (map? (or (:context event) {}))
                           (<= (count (pr-str (or (:context event) {}))) 65536))))
        (throw (ex-info "Invalid bounded Pi claim" {:id (:id event)})))
      (when (and (#{:runtime/started :runtime/reconciled :runtime/completed
                   :runtime/cancelled} (:kind event))
                 (not (and (string? (:child event)) (seq (:child event)))))
        (throw (ex-info "Pi child observation requires identity" {:id (:id event)})))
      (when (and (= :runtime/cancelled (:kind event))
                 (not (true? (:cleanup-verified event))))
        (throw (ex-info "Pi cancellation requires verified cleanup" {:id (:id event)}))))
    log))

(defn- assignment-events [observations assignment-id]
  (filterv #(= assignment-id (:assignment-id %)) (facts/ordered-facts observations)))
(defn- start-of [offer events]
  (or (first (filter #(= :runtime/requested (:kind %)) events)) offer))
(defn- model-events [session config offer controls observations assignment-id]
  (let [events (assignment-events observations assignment-id)
        start (start-of offer events)
        identity (trial-id session assignment-id)]
    (when start
      (when (and (:backend start) (not= (:backend start) (:plugin config)))
        (throw (ex-info "Pi assignment claim belongs to another selected backend" {:assignment-id assignment-id})))
      (let [initial (model/event identity "!start" 0 :start
                                {:backend (:plugin config) :task (:task start)})
            runtime (keep (fn [event]
                            (when-let [kind ({:runtime/started :launched
                                              :runtime/reconciled :reconciled
                                              :runtime/uncertain :uncertain
                                              :runtime/cancel-requested :cancel
                                              :runtime/completed :completed
                                              :runtime/cancelled :stopped
                                              :runtime/failed :failed} (:kind event))]
                              (model/event identity (:id event) (inc (:order event)) kind
                                           (select-keys event [:child :result :failure :cleanup-verified])))) events)
            cancellations (for [event (facts/ordered-facts controls)
                                :when (and (= :assignment/cancel-requested (:kind event))
                                           (= assignment-id (:assignment-id event)))]
                            (model/event identity (:id event) (inc (:order event)) :cancel {}))]
        (model/accumulate (model/empty-events identity)
                          (concat [initial] runtime cancellations))))))

(defn- requests [session config offer controls observations operations]
  (checked-log session controls)
  (normalize-observations session observations)
  (when (and offer (not (and (= session (:session offer))
                             (string? (:assignment-id offer)) (seq (:assignment-id offer))
                             (string? (:task offer)) (seq (:task offer))
                             (map? (:context offer))
                             (<= (count (pr-str (:context offer))) 65536))))
    (throw (ex-info "Invalid or foreign current Pi offer" {})))
  (let [config (transport/validate-config config)
        assignments (cond-> (into #{} (map :assignment-id)
                                  (facts/ordered-facts observations))
                      offer (conj (:assignment-id offer)))]
    (facts/from-facts session
      (keep (fn [assignment-id]
              (when-let [events (model-events session config
                                             (when (= assignment-id (:assignment-id offer)) offer)
                                             controls observations assignment-id)]
                (let [state (model/current-state events)
                      start (start-of (when (= assignment-id (:assignment-id offer)) offer)
                                      (assignment-events observations assignment-id))
                      request (assoc (model/request (trial-id session assignment-id) state)
                                     :context (or (:context start) {}))]
                  (when (operations (:operation request))
                    (facts/fact session (pr-str [:pi/request assignment-id (:operation request)])
                                0 :pi/request
                                {:assignment-id assignment-id :request request})))))
            (sort assignments)))))

(defn derive-execution-requests [session config offer observations]
  (requests session config offer (facts/empty-log session) observations
            #{:launch :collect :reconcile}))
(defn derive-control-requests [session config controls observations]
  (requests session config nil controls observations #{:stop :reconcile}))
(defn derive-completions [session observations]
  (normalize-observations session observations)
  (let [terminal (facts/select-kinds observations terminal-kinds)]
    (facts/from-facts session
      (keep (fn [assignment-id]
              (let [events (assignment-events observations assignment-id)
                    start (start-of nil events)]
                (when-not start
                  (throw (ex-info "Terminal Pi outcome lacks an owned assignment claim" {:assignment-id assignment-id})))
                ;; Only the outcome accepted by the canonical FSM is published.
                ;; A stopped receipt without a preceding cancel claim is not a result.
                (let [replayed (reduce
                                (fn [{:keys [state accepted] :as replay} event]
                                  (let [next-state (model/transition state event)]
                                    (if (and (not= state next-state)
                                             (#{:completed :cancelled :failed} (model/phase next-state)))
                                      {:state next-state :accepted (:id event)}
                                      (assoc replay :state next-state))))
                                {:state (model/initial-state) :accepted nil}
                                (model/ordered-events
                                 (model-events session {:plugin (:backend start)} nil
                                               (facts/empty-log session) observations assignment-id)))]
                  (first (filter #(= (:accepted replayed) (:id %)) events)))))
            (distinct (map :assignment-id (facts/ordered-facts terminal)))))))
(defn derive-status [session observations]
  (normalize-observations session observations))

(defn requested-fact
  "Publish a claim before external dispatch. The pump must revalidate current offer."
  [session assignment-id order request]
  (when-not (= (trial-id session assignment-id) (:session request))
    (throw (ex-info "Foreign Pi request claim" {:assignment-id assignment-id})))
  (facts/fact session (pr-str [:runtime/requested assignment-id]) order :runtime/requested
              {:assignment-id assignment-id :task (:task request) :backend (:backend request)
               :context (or (:context request) {})}))
(defn cancel-requested-fact [session assignment-id order request]
  (when-not (and (= :stop (:operation request))
                 (= (trial-id session assignment-id) (:session request)))
    (throw (ex-info "Invalid Pi cancellation claim" {:assignment-id assignment-id})))
  (facts/fact session (pr-str [:runtime/cancel-requested assignment-id]) order
              :runtime/cancel-requested {:assignment-id assignment-id :child (:child request)}))

(defn observation-fact [session assignment-id observation]
  (when-not (= (trial-id session assignment-id) (:session observation))
    (throw (ex-info "Foreign Pi trial observation" {:assignment-id assignment-id})))
  (let [kind ({:launched :runtime/started :reconciled :runtime/reconciled
               :uncertain :runtime/uncertain :completed :runtime/completed
               :stopped :runtime/cancelled :failed :runtime/failed
               :progress :runtime/progress} (:kind observation))]
    (when-not kind (throw (ex-info "Unsupported model observation" {:kind (:kind observation)})))
    (facts/fact session (:id observation) (:order observation) kind
                (assoc (select-keys observation [:child :result :failure :cleanup-verified])
                       :assignment-id assignment-id))))

(defn current-request-intents
  "The CURRENT Pi intents only: recomputed from the present offer, controls and
  observations, and retracted when they stop being current. This is the graph-side
  owner of Pi eligibility; the delivery pump never recomputes it."
  [session config offer controls observations]
  (->> (facts/merge-logs (derive-execution-requests session config offer observations)
                         (derive-control-requests session config controls observations))
       (facts/ordered-facts)
       (mapv #(select-keys % [:session :id :order :kind :assignment-id :request]))))

(def pi-request-kind
  "The native boundary kind of this component's effect. Effect identity is
  [kind intent-id], the same identity scheme the runtime's own effect-operator
  builds with :effect/identity-parts."
  :harness/pi-request)

(defn deliver-intent
  "Session-owned Pi delivery handler (registered natively, resolved from this
  qualified symbol). Claims are published by the pump before invocation. Outside
  pump ownership the effect is only declared: nothing leaves this session."
  [services state request]
  (if-not (primitives/transport-armed?)
    (primitives/deferred-receipt state request)
    (let [intent (:boundary/payload request)
          order (:order services)]
      (when-not (= [pi-request-kind (:id intent)] (:boundary/id request))
        (throw (ex-info "Pi effect identity does not match its intent"
                        {:effect-id (:boundary/id request)})))
      {:state state
       :receipt {:status :delivered
                 :observations (transport/dispatch! (:pi services) (:assignment-id intent)
                                                    (assoc (:request intent) :order order))}})))

(def pi-request-effect
  {:effect/symbol 'pi:declare-request
   :boundary/port :harness
   :boundary/kind pi-request-kind
   :effect/arities #{1}
   :effect/handler-symbol 'examples.lain.subagents.pi-extension/deliver-intent
   :effect/identity-parts (comp vector :id)
   :effect/normalize (fn [_ [intents]]
                       (cond
                         (nil? intents) {:status :wait}
                         (not (every? map? intents))
                         {:status :invalid :reason :invalid-intents
                          :message "pi:declare-request needs bounded intent maps"
                          :data {:intents (count intents)}}
                         :else {:status :ready :payload (vec intents)}))})

(defn session-extension [session config]
  (transport/validate-config config)
  (extension/extension-bundle
   {:id ::pi
    :bindings
    (into
     (mapv (fn [[name input-kinds f]]
             [name (primitives/leaf-operator name
                     {:arity (count input-kinds) :inputs (vec (range (count input-kinds)))
                      :input-kinds input-kinds :output nil :session session :kind :facts} f)])
           [['pi:normalize-observations [:facts] #(normalize-observations session %)]
            ['pi:derive-execution-requests [:value :facts] #(derive-execution-requests session config %1 %2)]
            ['pi:derive-control-requests [:facts :facts] #(derive-control-requests session config %1 %2)]
            ['pi:derive-completions [:facts] #(derive-completions session %)]
            ['pi:derive-status [:facts] #(derive-status session %)]])
     [['pi:current-requests
       (primitives/leaf-operator 'pi:current-requests
        {:arity 3 :inputs [0 1 2] :input-kinds [:value :facts :facts]
         :output nil :session session :kind :value}
        (partial current-request-intents session config))]
      ['pi:emit-requests (primitives/intent-effect-operator pi-request-effect)]])
    :effects [pi-request-effect]}))
