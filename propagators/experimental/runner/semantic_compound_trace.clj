(ns propagators.experimental.runner.semantic-compound-trace
  "Topology-only spawn tracing selected by propagator activation semantics."
  (:require [propagators.cells.cell :as cell]
            [propagators.core :as core]
            [propagators.experimental.runner.compound-trace :as trace]
            [propagators.experimental.runner.drivers :as drivers]
            [propagators.experimental.runner.examples :as examples]
            [propagators.experimental.runner.task-policy :as task-policy]
            [propagators.network-patch :as patch]
            [propagators.message :as msg]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.runner-constructor :as constructor]))

(defn- network-valued-target?
  [network target-id]
  (let [entry (net/network-env-lookup network target-id)]
    (and (cell/cell? entry)
         (net/net? (cell/cell-strongest entry)))))

(defn- indirect-target?
  [network target-id]
  (not (cell/cell? (net/network-env-lookup network target-id))))

(defn topology-candidate?
  "True when applying an activation result may alter propagator topology."
  [activation-result network]
  (let [{:keys [messages effects]}
        (patch/normalize-activation-return activation-result)]
    (or (seq effects)
        (some (fn [message]
                (let [target-id (msg/message-id message)]
                  (or (net/net? (msg/message-value message))
                      (network-valued-target? network target-id)
                      (indirect-target? network target-id))))
              messages))))

(defn semantic-envelope
  [origin activation-result]
  {::origin origin
   ::activation-result activation-result})

(defn semantic-envelope?
  [value]
  (and (map? value)
       (contains? value ::origin)
       (contains? value ::activation-result)))

(defn- origin
  [propagator-id network]
  (let [propagator (net/network-lookup-propagator network propagator-id)]
    {:propagator-id propagator-id
     :propagator-kind (prop/prop-name propagator)
     :network-path [:outer]}))

(defn semantic-propagator-evaluator
  "Annotate topology-capable results; pass ordinary results through unchanged."
  [record!]
  (fn [propagator-id network {:keys [success fail]}]
    (try
      (let [[activation-result propagated-network]
            (examples/evaluate-propagator propagator-id network)]
        (if (topology-candidate? activation-result propagated-network)
          (do
            (record! :topology-candidates)
            (success (semantic-envelope
                      (origin propagator-id network)
                      activation-result)
                     propagated-network))
          (do
            (record! :ordinary-results)
            (success activation-result propagated-network))))
      (catch Throwable error
        (fail error)))))

(defn semantic-patch-evaluator
  "Trace actual spawns for annotated results; directly apply ordinary results."
  [observe record!]
  (fn [result network {:keys [success fail]}]
    (try
      (if (semantic-envelope? result)
        (let [activation-result (::activation-result result)
              [new-tasks patched-network]
              (core/eval-activation-result activation-result network)
              spawns (trace/scoped-spawn-diff activation-result
                                              network
                                              patched-network)]
          (record! :topology-diffs)
          (doseq [spawn spawns]
            (observe {:event :propagator/spawned
                      :cause (::origin result)
                      :spawn spawn}))
          (success new-tasks patched-network))
        (let [[new-tasks patched-network]
              (core/eval-activation-result result network)]
          (record! :ordinary-applies)
          (success new-tasks patched-network)))
      (catch Throwable error
        (fail error)))))

(defn semantic-runner
  [observe record!]
  (drivers/trampoline-runner
   (constructor/network-iterator-constructor
    task-policy/fifo-task-policy
    (semantic-propagator-evaluator record!)
    (semantic-patch-evaluator observe record!))))

(defn run-with-semantic-trace
  "Run with topology-only tracing and return result, spawn events, and path counts."
  [state]
  (let [events (atom [])
        metrics (atom {})
        record! #(swap! metrics update % (fnil inc 0))
        result ((semantic-runner #(swap! events conj %) record!) state)]
    {:result result
     :events @events
     :metrics @metrics}))
