(ns propagators.runner
  "Continuation-constructed propagation runner."
  (:require [propagators.cell-evaluator :as cell-evaluator]
            [propagators.cells.merge :as merge]
            [propagators.combinator :as combinator]
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

(def network-iterator-key
  [:runner :network-iterator])

(declare network-iterator)

(defn with-network-iterator
  "Attach the iterator selected by the owning session to an immutable Net."
  [network iterator]
  (net/assoc-net-dict-entry network network-iterator-key iterator))

(defn without-network-iterator
  [network]
  (net/net-with-dict network
                     (dissoc (net/net-dict-or-empty network)
                             network-iterator-key)))

(defn selected-network-iterator
  [network]
  (or (net/network-dict-entry network network-iterator-key)
      (network-iterator)))

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

(defn make-patch-evaluator
  "Construct activation patch evaluation around an explicit cell merge."
  [merge-content]
  (let [evaluate-cell-message (cell-evaluator/make-cell-evaluator merge-content)
        apply-cell-patch (fn [_emitter cell-message network]
                           (evaluate-cell-message cell-message network))
        apply-accessor-patch
        (compound-patch/make-accessor-patch-applier evaluate-cell-message)
        apply-patch
        (combinator/branch
         patch/declaration-patch? patch/apply-declaration-patch
         compound-patch/accessor-patch? apply-accessor-patch
         patch/cell-patch? apply-cell-patch
         patch/reject-patch)]
    (fn [evaluated network {:keys [success fail]}]
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
            (fail error)))))))

(def evaluate-patches
  (make-patch-evaluator merge/cell-merge))

(defn network-iterator
  []
  (constructor/network-iterator-constructor
   fifo-task-policy
   evaluate-propagator
   evaluate-patches))

(defn run-network-with
  "Run `tasks` with an explicit iterator until quiescence."
  [iterator tasks network]
  (let [last-network (atom network)
        advance (*advance-transform* iterator)]
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

(defn run-network
  "Run `tasks` with the iterator explicitly carried by this network.

  Ordinary networks carry no selection and retain the standard iterator."
  [tasks network]
  (run-network-with (selected-network-iterator network) tasks network))

(defn completed-network
  "Return the completed network or throw the runner failure."
  [{:keys [status network error] :as execution}]
  (case status
    :completed network
    :failed (throw error)
    (throw (ex-info "unknown runner execution status"
                    {:execution execution}))))
