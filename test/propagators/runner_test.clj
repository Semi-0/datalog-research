(ns propagators.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.experimental.runner.compound-chain :as chain]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]
            [propagators.runner :as runner]))

(defn- completed
  [execution]
  (runner/completed-network execution))

(deftest relationship-store-indexes-both-directions-idempotently
  (let [parent-key (relationship/node-key [:outer] :parent)
        child-key (relationship/node-key [:outer [:cell :nested]] :child)
        store (-> relationship/empty-relationship
                  (relationship/relate parent-key child-key)
                  (relationship/relate parent-key child-key))]
    (is (= #{child-key}
           (relationship/children store parent-key)))
    (is (= #{parent-key}
           (relationship/parents store child-key)))))

(defn- propagator-for-output
  [network output-id expected-name]
  (some
   (fn [[id entry]]
     (when (and (prop/prop? entry)
                (= expected-name (prop/prop-name entry))
                (contains? (graph/node-output-ids
                            (graph/get-node (net/net-graph network) id))
                           output-id))
       id))
   (net/net-env network)))

(defn- nested-propagator-name
  [outer-network [network-path node-id]]
  (let [[_ [_ collection-id]] network-path
        nested-network (net/network-cell-value outer-network collection-id)
        entry (net/network-env-lookup nested-network node-id)]
    (when (prop/prop? entry)
      (prop/prop-name entry))))

(deftest runner-reaches-quiescence-and-records-compound-ancestry
  (let [built (chain/build-vanilla-chain 1)
        execution (runner/run-network (:tasks built) (:network built))
        final-network (completed execution)
        parent-id (propagator-for-output
                   (:network built)
                   (:output built)
                   [:compound-object/network-slot :car])
        parent-key (relationship/node-key [:outer] parent-id)
        children (relationship/children
                  (net/net-relationship final-network)
                  parent-key)
        child-names (set (keep #(nested-propagator-name final-network %)
                               children))]
    (is (= :completed (:status execution)))
    (is (= (net/network-cell-value final-network (:input built))
           (net/network-cell-value final-network (:output built))))
    (is (some? parent-id))
    (is (contains? child-names
                   [:compound-object/slot-sync :car :to-canonical]))
    (is (contains? child-names
                   [:compound-object/slot-sync :car :from-canonical]))
    (doseq [child children]
      (is (= #{parent-key}
             (relationship/parents
              (net/net-relationship final-network)
              child))))))

(deftest ordinary-update-creates-no-spawn-relationships
  (let [built (chain/build-vanilla-chain 3)
        settled (completed
                 (runner/run-network (:tasks built) (:network built)))
        [seeded tasks] (nb/seed-cell! settled
                                      tq/empty-queue
                                      (:input built)
                                      30)
        execution (runner/run-network tasks seeded)
        final-network (completed execution)]
    (is (= 30
           (net/network-cell-value final-network (:output built))))
    (is (= (net/net-relationship settled)
           (net/net-relationship final-network)))))
