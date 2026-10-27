(ns examples.lain.subagents.primitives
  "Leaf policy operators and a session-scoped fact union runner. Actor graphs
  remain connected; only explicitly typed fact leaves participate in union."
  (:require [examples.lain.subagents.facts :as facts]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur.flat :as gur]
            [propagators.helpers.task-queue :as queue]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.runner-constructor :as constructor]
            [propagators.stdlib.observation :as observation]))

(defn- checked-log [session log]
  (when-not (and (facts/log? log) (= session (facts/session-id log)))
    (throw (ex-info "Expected facts owned by this session" {:session session :type (type log)})))
  log)

(defn- leaf-value [session kind network id]
  (let [candidate (data/payload (net/network-cell-strongest network id))]
    (when (value/contradiction? candidate)
      (throw (ex-info "Contradictory policy input" {:cell id})))
    (case kind
      :facts (if (value/nothing? candidate) (facts/empty-log session) (checked-log session candidate))
      :value (if (value/nothing? candidate) nil candidate)
      (throw (ex-info "Unsupported policy input kind" {:kind kind})))))

(defn- apply-fact-patch [session emitter candidate network]
  (if-not (message/message? candidate)
    (runner/apply-patch emitter candidate network)
    (let [id (message/message-id candidate)
          update (data/payload (message/message-value candidate))
          previous (data/payload (net/network-cell-strongest network id))]
      (cond
        (facts/log? update)
        (let [update (checked-log session update)
              merged (cond
                       (value/nothing? previous) update
                       (facts/log? previous) (facts/merge-logs (checked-log session previous) update)
                       :else (throw (ex-info "Fact port contains incompatible information"
                                             {:cell id :existing-type (str (type previous))})))
              entry (net/network-lookup-cell network id)]
          (if (= previous merged)
            [queue/empty-queue network]
            [(queue/into-queue (nb/neighbor-propagator-ids network id))
             (net/assoc-net-cell network id (cell/cell (cell/cell-name entry) merged merged))]))

        (and (facts/log? previous) (value/nothing? update))
        [queue/empty-queue network]

        :else (runner/apply-patch emitter candidate network)))))

(defn- evaluate-fact-patches [session evaluated network {:keys [success fail]}]
  (try
    (let [{:keys [messages effects]} (patch/normalize-activation-return (:activation-result evaluated))
          emitter (:emitter evaluated)]
      (loop [remaining (seq (concat effects messages)) current network tasks queue/empty-queue]
        (if-let [candidate (first remaining)]
          (let [[next-tasks updated] (apply-fact-patch session emitter candidate current)]
            (recur (next remaining) updated (queue/merge-queues tasks next-tasks)))
          (success tasks current))))
    (catch Throwable error (fail error))))

(defn runner-transform
  "Use only in the owning session's runner composition. This specializes the
  ordinary FIFO iterator's patch evaluator; native patches retain their handlers.
  Compose observation/history transforms outside this domain iterator. No global
  merge method or process-wide mutable table is installed."
  [session]
  (facts/empty-log session)
  (fn [_ordinary-iterator]
    (constructor/network-iterator-constructor
     runner/fifo-task-policy runner/evaluate-propagator
     (partial evaluate-fact-patches session))))

(defn publish-facts!
  "Publish monotone facts through the owning session's domain input boundary.
  Core scalar publication cannot union these leaves before the runner starts."
  [session-atom session-id cell-id log]
  (checked-log session-id log)
  (locking session-atom
    (let [network (:program/net @session-atom)
          [tasks updated] (apply-fact-patch session-id nil (message/message cell-id log) network)
          settled (binding [runner/*advance-transform*
                            (runner/compose-advance-transforms
                             runner/*advance-transform* (runner-transform session-id))]
                    (runner/completed-network (runner/run-network tasks updated)))]
      (swap! session-atom assoc :program/net settled)
      session-atom)))

(defn- selected-inputs [spec arguments]
  (when-not (= (:arity spec) (count arguments))
    (throw (ex-info "Policy arity mismatch" {:expected (:arity spec) :actual (count arguments)})))
  (mapv #(nth arguments %) (:inputs spec)))

(defn- calculate [spec f network inputs]
  (apply f (mapv #(leaf-value (:session spec) %1 network %2) (:input-kinds spec) inputs)))

(defn- input-watch-facts [session network inputs]
  (facts/from-facts
   session
   (mapcat (fn [index input]
             (map (fn [event]
                    (facts/fact session (str "policy/watch/" (pr-str [index (:id event)]))
                                (:order event) :policy/input
                                {:input index :fact event}))
                  (facts/ordered-facts (leaf-value session :facts network input))))
           (range) inputs)))

(defn- install-value [name spec f network arguments fallback]
  (let [inputs (selected-inputs spec arguments)
        output (if (some? (:output spec)) (nth arguments (:output spec)) fallback)
        watch (gur/stable-node-id [::watch output])
        source (gur/stable-node-id [::source output])
        prepared (reduce nb/ensure-cell network [watch source output])
        [watch-id watched]
        ((prop/construct-propagator
          [::watch name]
          (fn [_ _ current]
            [(message/message watch
               (input-watch-facts (:session spec) current inputs))])
          inputs [watch]) prepared)
        [publication-id published]
        ((publication/p:observe
          (gur/stable-node-id [::publish output]) watch source
          (fn [current]
            (let [result (calculate spec f current inputs)]
              (if (nil? result) value/nothing result)))) watched)
        [forward-id installed] ((observation/identity [::value name] source output) published)]
    [installed [watch-id publication-id forward-id] output]))

(defn leaf-operator
  "Lift a pure policy over declared leaf arguments. Fact inputs have an explicit
  empty identity. Current-value output is TTMS published and may withdraw via nil."
  [name {:keys [arity inputs input-kinds session kind] :or {kind :facts} :as spec} f]
  (facts/empty-log session)
  (when-not (and (pos-int? arity) (vector? inputs) (vector? input-kinds)
                 (= (count inputs) (count input-kinds))
                 (every? #(and (integer? %) (<= 0 %) (< % arity)) inputs)
                 (every? #{:facts :value} input-kinds) (ifn? f)
                 (contains? #{:facts :value} kind))
    (throw (ex-info "Invalid leaf policy declaration" {:name name})))
  (let [spec (assoc spec :kind kind)]
    (case kind
      :value
      (do
        (when-not (every? #{:facts} input-kinds)
          (throw (ex-info "Current-value policy watches fact inputs only" {:name name})))
        (operator/operator-closure {:name name :install (partial install-value name spec f)}))
      :facts
      (operator/propagator-operator
       {:name name
        :input-selector (fn [arguments _fallback _context] (selected-inputs spec arguments))
        :output-selector (fn [arguments fallback]
                           (if (some? (:output spec)) (nth arguments (:output spec)) fallback))
        :messages (fn [network {:keys [inputs outputs]}]
                    (let [result (checked-log session (calculate spec f network inputs))]
                      [(message/message (first outputs) result)]))}))))
