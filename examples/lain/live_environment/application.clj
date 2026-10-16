(ns examples.lain.live-environment.application
  "Opt-in runtime application evaluator. Compiler and GUR code remain unchanged.
  Runtime metadata supplies call ports; callable/procedure layers own support."
  (:require [examples.lain.live-environment.callable :as callable]
            [propagators.combinator :as combinator]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.datastructures.layered-value :as datum]
            [propagators.gur.flat :as gur]
            [propagators.helpers.task-queue :as queue]
            [propagators.network :as net]
            [propagators.relationship :as relationship]
            [propagators.runner :as runner]
            [propagators.runner-constructor :as constructor]))

(defn call-context
  "Read the existing runtime call ports; does not invent binding or owner state."
  [propagator-id network]
  (some (fn [{:keys [application-id operator-id argument-ids context-id result-id]}]
          (if (= propagator-id (gur/stable-node-id [application-id :apply-prop]))
            {:call-id propagator-id :source-id operator-id
             :argument-ids argument-ids :context-id context-id :output-id result-id}
            nil))
        (application/application-topologies network)))

(defn- supported-application-step? [{:keys [network tasks]} _continuations]
  (if (queue/queue-empty? tasks)
    false
    (let [[id _remaining] (queue/pop-task tasks)
          context (call-context id network)]
      (if context
        (datum/layer-present?
         (net/network-cell-strongest network (:source-id context)) :base)
        false))))

(defn- evaluate-application [id network {:keys [success fail]}]
  (try
    (if-let [context (call-context id network)]
      (success {:emitter (relationship/node-key [:outer] id)
                :activation-result (callable/activation context network)}
               network)
      (fail (ex-info "Supported application has no declared call context" {:id id})))
    (catch Throwable error
      (fail error))))

(defn advance-transform
  "Select a composed application evaluator before raw-callable dispatch.
  Ordinary tasks, empty completion and their failure path retain `advance`.
  This bridge scans existing metadata; it is a feasibility probe, not an
  optimized application index or a session-wide production runner change."
  [advance]
  (combinator/branch
   supported-application-step?
   (constructor/network-iterator-constructor
    runner/fifo-task-policy evaluate-application runner/evaluate-patches)
   advance))
