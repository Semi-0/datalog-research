(ns propagators.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.experimental.runner.compound-chain :as chain]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]
            [propagators.semantic-relationships :as relationships]))

(defn- completed
  [execution]
  (runner/completed-network execution))

(deftest relationship-store-indexes-both-directions-idempotently
  (let [parent {:propagator-id :parent
                :propagator-kind :parent-kind
                :network-path [:outer]}
        child {:propagator-id :child
               :propagator-kind :child-kind
               :network-path [:outer [:cell :nested]]}
        parent-key (relationships/semantic-node-key
                    (:network-path parent)
                    (:propagator-id parent))
        child-key (relationships/semantic-node-key
                   (:network-path child)
                   (:propagator-id child))
        store (-> relationships/empty-store
                  (relationships/add-spawn parent child)
                  (relationships/add-spawn parent child))]
    (is (= #{child-key}
           (relationships/spawned-children store parent-key)))
    (is (= #{parent-key}
           (relationships/spawned-parents store child-key)))))

(deftest runner-reaches-quiescence-and-records-compound-ancestry
  (let [built (chain/build-vanilla-chain 1)
        execution (runner/run-network (:tasks built) (:network built))
        final-network (completed execution)
        store (:semantic-relationships execution)
        entities (:entities store)
        network-slot-keys
        (for [[key descriptor] entities
              :when (= :compound-object/network-slot
                       (first (:propagator-kind descriptor)))]
          key)
        spawned-kinds
        (set
         (mapcat
          (fn [parent-key]
            (map #(get-in entities [% :propagator-kind])
                 (relationships/spawned-children store parent-key)))
          network-slot-keys))]
    (is (= :completed (:status execution)))
    (is (= (net/network-cell-value final-network (:input built))
           (net/network-cell-value final-network (:output built))))
    (is (seq (:present-at-start store)))
    (is (contains? spawned-kinds
                   [:compound-object/slot-sync :car :to-canonical]))
    (is (contains? spawned-kinds
                   [:compound-object/slot-sync :car :from-canonical]))))

(deftest ordinary-update-creates-no-spawn-relationships
  (let [built (chain/build-vanilla-chain 3)
        settled (completed
                 (runner/run-network (:tasks built) (:network built)))
        [seeded tasks] (nb/seed-cell! settled
                                      tq/empty-queue
                                      (:input built)
                                      30)
        execution (runner/run-network tasks seeded)
        relation-graph
        (get-in execution
                [:semantic-relationships :relations
                 relationships/spawned-relation])]
    (is (= 30
           (net/network-cell-value (completed execution) (:output built))))
    (is (every? (fn [[_ node]]
                  (and (empty? (graph/node-input-ids node))
                       (empty? (graph/node-output-ids node))))
                relation-graph))))
