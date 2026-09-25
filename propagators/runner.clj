(ns propagators.runner
  "Continuation-constructed propagation runner."
  (:require [propagators.combinator :as combinator]
            [propagators.datastructures.compound-object.patch :as compound-patch]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]
            [propagators.runner-constructor :as constructor]))

(def fifo-task-policy
  {:tasks-empty? tq/queue-empty?
   :take-task (fn [_network tasks]
                (tq/pop-task tasks))
   :add-tasks (fn [remaining new-tasks]
                (tq/merge-queues remaining (tq/into-queue new-tasks)))})

(def ^:dynamic *advance-transform*
  "Domain-neutral composition over one complete runner transition."
  identity)

(defn compose-advance-transforms
  "Compose transforms left-to-right around one iterator transition."
  [& transforms]
  (fn [advance]
    (reduce (fn [composed transform]
              (transform composed))
            advance
            (reverse transforms))))

(defn- propagator-emitter
  [propagator-id]
  (relationship/node-key [:outer] propagator-id))

(defn evaluate-propagator
  [propagator-id network {:keys [success fail]}]
  (try
    (let [node (graph/get-node (net/net-graph network) propagator-id)
          inputs (graph/node-input-ids node)
          outputs (graph/node-output-ids node)
          propagator (net/network-lookup-propagator network propagator-id)
          activation-result ((prop/prop-f propagator)
                             inputs
                             outputs
                             network)]
      (success {:emitter (propagator-emitter propagator-id)
                :activation-result activation-result}
               network))
    (catch Throwable error
      (fail error))))

(defn evaluated-activation?
  [value]
  (and (map? value)
       (contains? value :emitter)
       (contains? value :activation-result)))

(def apply-patch
  (combinator/branch
   patch/declaration-patch? patch/apply-declaration-patch
   compound-patch/accessor-patch? compound-patch/apply-accessor-patch
   patch/cell-patch? patch/apply-cell-patch
   patch/reject-patch))

(defn evaluate-patches
  [evaluated network {:keys [success fail]}]
  (if-not (evaluated-activation? evaluated)
    (fail (ex-info "patch evaluator expected evaluated activation"
                   {:value evaluated}))
    (try
      (let [{:keys [messages effects]}
            (patch/normalize-activation-return
             (:activation-result evaluated))
            emitter (:emitter evaluated)
            ordered-patches (concat effects messages)]
        (loop [remaining (seq ordered-patches)
               tasks tq/empty-queue
               current network]
          (if (nil? remaining)
            (success tasks current)
            (let [[new-tasks next-network]
                  (apply-patch emitter (first remaining) current)]
              (recur (next remaining)
                     (tq/merge-queues tasks new-tasks)
                     next-network)))))
      (catch Throwable error
        (fail error)))))

(defn network-iterator
  []
  (constructor/network-iterator-constructor
   fifo-task-policy
   evaluate-propagator
   evaluate-patches))

(defn run-network
  "Run `tasks` until quiescence and return the final immutable Net."
  [tasks network]
  (let [last-network (atom network)
        advance (*advance-transform* (network-iterator))]
    (letfn [(bounce [{:keys [network] :as state}]
              (reset! last-network network)
              (fn []
                (try
                  (advance
                   state
                   {:done
                    (fn [final-network]
                      {:status :completed
                       :network final-network})

                    :fail
                    (fn [error]
                      {:status :failed
                       :error error
                       :network @last-network})

                    :continue bounce})
                  (catch Throwable error
                    {:status :failed
                     :error error
                     :network @last-network}))))]
      (trampoline bounce
                  {:network network
                   :tasks (tq/into-queue tasks)}))))

(defn completed-network
  "Return the completed network or throw the runner failure."
  [{:keys [status network error] :as execution}]
  (case status
    :completed network
    :failed (throw error)
    (throw (ex-info "unknown runner execution status"
                    {:execution execution}))))
