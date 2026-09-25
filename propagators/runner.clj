(ns propagators.runner
  "Continuation-constructed propagation runner with semantic relationships."
  (:require [propagators.cells.cell :as cell]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.runner-constructor :as constructor]
            [propagators.semantic-relationships :as relationships]))

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

(defn- propagator-origin
  [propagator-id network]
  (let [propagator (net/network-lookup-propagator network propagator-id)]
    {:propagator-id propagator-id
     :propagator-kind (prop/prop-name propagator)
     :network-path [:outer]}))

(defn evaluate-originated-propagator
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
      (success {:origin (propagator-origin propagator-id network)
                :activation-result activation-result}
               network))
    (catch Throwable error
      (fail error))))

(defn originated-activation?
  [value]
  (and (map? value)
       (contains? value :origin)
       (contains? value :activation-result)))

(defn- direct-cell-target?
  [network target-id]
  (cell/cell? (net/network-env-lookup network target-id)))

(defn- add-spawned!
  [store origin before after]
  (doseq [spawned (relationships/spawned before after)]
    (swap! store relationships/add-spawn origin spawned)))

(defn- declared-propagator
  [declaration before after]
  (let [id (:id declaration)]
    (when (and (= :network/declare-propagator (:op declaration))
               (not (contains? (net/net-env before) id)))
      (propagator-origin id after))))

(defn- topology-bearing-message?
  [cell-message network]
  (let [target-id (message/message-id cell-message)]
    (if-not (ids/node-id? target-id)
      true
      (let [current (net/network-env-lookup network target-id)]
        (or (not (direct-cell-target? network target-id))
            (net/net? (message/message-value cell-message))
            (and (cell/cell? current)
                 (net/net? (cell/cell-strongest current))))))))

(defn- record-spawned!
  [store origin candidate before after]
  (cond
    (patch/network-declaration? candidate)
    (when-let [spawned (declared-propagator candidate before after)]
      (swap! store relationships/add-spawn origin spawned))

    (message/message? candidate)
    (when (topology-bearing-message? candidate before)
      (add-spawned! store origin before after))

    :else
    nil))

(defn patch-evaluator
  [relationship-store]
  (fn [originated network {:keys [success fail]}]
    (if-not (originated-activation? originated)
      (fail (ex-info "patch evaluator expected originated activation"
                     {:value originated}))
      (try
        (let [{:keys [messages effects semantic-relationships]}
              (patch/normalize-activation-return
               (:activation-result originated))
              origin (:origin originated)
              ordered-patches (concat effects messages)]
          (when semantic-relationships
            (swap! relationship-store
                   relationships/merge-stores
                   semantic-relationships))
          (loop [remaining (seq ordered-patches)
                 tasks tq/empty-queue
                 current network]
            (if (nil? remaining)
              (success tasks current)
              (let [current-patch (first remaining)
                    [new-tasks next-network]
                    (patch/apply-patch current-patch current)]
                (record-spawned! relationship-store
                                 origin
                                 current-patch
                                 current
                                 next-network)
                (recur (next remaining)
                       (tq/merge-queues tasks new-tasks)
                       next-network)))))
        (catch Throwable error
          (fail error))))))

(defn network-iterator
  [relationship-store]
  (constructor/network-iterator-constructor
   fifo-task-policy
   evaluate-originated-propagator
   (patch-evaluator relationship-store)))

(defn run-network
  "Run `tasks` until quiescence and return the final Net plus relationships."
  [tasks network]
  (let [relationship-store (atom (relationships/from-network network))
        last-network (atom network)
        advance (*advance-transform* (network-iterator relationship-store))]
    (letfn [(bounce [{:keys [network] :as state}]
              (reset! last-network network)
              (fn []
                (try
                  (advance
                   state
                   {:done
                    (fn [final-network]
                      {:status :completed
                       :network final-network
                       :semantic-relationships @relationship-store})

                    :fail
                    (fn [error]
                      {:status :failed
                       :error error
                       :network @last-network
                       :semantic-relationships @relationship-store})

                    :continue bounce})
                  (catch Throwable error
                    {:status :failed
                     :error error
                     :network @last-network
                     :semantic-relationships @relationship-store}))))]
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
