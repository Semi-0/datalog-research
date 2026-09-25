(ns propagators.experimental.runner.examples
  "Runnable compositions over the existing propagator activation contracts."
  (:require [propagators.core :as core]
            [propagators.experimental.runner.controlled :as controlled]
            [propagators.experimental.runner.drivers :as drivers]
            [propagators.experimental.runner.task-policy :as task-policy]
            [propagators.graph :as graph]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.runner-constructor :as constructor]))

(defn evaluate-propagator
  "Activate one existing propagator and expose its descriptions as patches."
  [propagator-id network]
  (let [node (graph/get-node (net/net-graph network) propagator-id)
        inputs (graph/node-input-ids node)
        outputs (graph/node-output-ids node)
        activate (prop/prop-f (net/env-get (net/net-env network) propagator-id))]
    [(activate inputs outputs network) network]))

(defn evaluate-patches
  "Apply existing activation descriptions through the current core boundary."
  [patches network]
  (core/eval-activation-result patches network))

(def propagator-evaluator-cps
  (fn [propagator-id network {:keys [success fail]}]
    (try
      (let [[patches propagated-network]
            (evaluate-propagator propagator-id network)]
        (success patches propagated-network))
      (catch Throwable error
        (fail error)))))

(def patch-evaluator-cps
  (fn [patches network {:keys [success fail]}]
    (try
      (let [[new-tasks patched-network] (evaluate-patches patches network)]
        (success new-tasks patched-network))
      (catch Throwable error
        (fail error)))))

(defn network-iterator
  [task-policy]
  (constructor/network-iterator-constructor task-policy
                                            propagator-evaluator-cps
                                            patch-evaluator-cps))

(def normal-runner
  (drivers/trampoline-runner
   (network-iterator task-policy/fifo-task-policy)))

(defn ranked-round-robin-runner
  [score]
  (drivers/trampoline-runner
   (network-iterator (task-policy/ranked-round-robin-task-policy score))))

(defn async-runner
  [executor]
  (drivers/scheduler-runner
   (drivers/executor-scheduler executor)
   (network-iterator task-policy/fifo-task-policy)))

(defn controlled-runner
  [executor]
  (controlled/controlled-scheduler-runner
   (drivers/executor-scheduler executor)
   #(.shutdownNow executor)
   (network-iterator task-policy/fifo-task-policy)))
