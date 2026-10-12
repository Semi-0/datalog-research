(ns propagators.experimental.amb-runtime-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.amb.contracts :as c]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.primitives :as p]
            [propagators.experimental.amb.publication :as publication]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]
            [propagators.stdlib.prop :as standard]
            [propagators.stdlib.premise-state :as state]
            [propagators.stdlib.supported-procedure :as supported]))

(defn update-cell [network id payload]
  (let [[tasks patched] (core/eval-cell id (message/message id payload) network)]
    (nb/run-propagators patched tasks)))

(defn publish [network id base]
  (update-cell network id
               (collection/content (:value (publication/snapshot-message network id base)))))

(deftest builder-choice-withdraws-through-two-computations-and-recovers
  (let [[domain repairs control a b z] (repeatedly 6 ids/new-node-id)
        initial (nb/install-cells [domain repairs control a b z])
        [choice n1] ((p/p:amb domain repairs control a) (nb/seed-cell initial domain [1 2]))
        [n2 s1 _] (supported/install (supported/from-primitive standard/id) n1 [a] b)
        [n3 s2 _] (supported/install (supported/from-primitive standard/id) n2 [b] z)
        ready (nb/run-propagators n3 (concat [choice] s1 s2))
        request {:action :withdraw :target a :expected-timestamp 1 :next-index 1 :reason #{}}
        withdrawn (publish ready repairs request)
        choose (assoc request :action :choose :expected-timestamp 2)
        restored (publish withdrawn repairs choose)
        replay (nb/run-propagators restored [choice])]
    (is (= 1 (observation/cell-base ready z)))
    (is (value/unusable? (net/network-cell-strongest withdrawn z)))
    (is (= 2 (observation/cell-base restored z)))
    (is (= #{(c/premise (observation/cell-base restored control))}
           (datum/support-of (net/network-cell-strongest restored z))))
    (is (= (net/net-env restored) (net/net-env replay)))
    (is (= (set (keys (net/net-env ready))) (set (keys (net/net-env restored)))))
    (is (= 1 (:timestamp (observation/cell-base ready control))))
    (is (= 3 (:timestamp (observation/cell-base restored control))))))

(deftest require-does-not-call-unusable-information-false
  (let [[predicate failure] (repeatedly 2 ids/new-node-id)
        [id initial] ((p/p:require predicate failure) net/empty-net)
        pending (nb/run-propagators initial [id])
        true-net (publish pending predicate true)
        false-net (publish true-net predicate false)]
    (is (= :pending (:status (observation/cell-base pending failure))))
    (is (= :satisfied (:status (observation/cell-base true-net failure))))
    (is (= :failed (:status (observation/cell-base false-net failure))))
    (let [source (ids/new-node-id)
          bad (collection/content {:base false :support #{{:source source :timestamp 1
                                                          :premises-status :active}}})
          inconsistent (update-cell pending predicate bad)]
      (is (= :pending (:status (observation/cell-base inconsistent failure)))))))

(deftest contradictory-and-stale-predicates-are-not-logical-failures
  (let [[source predicate failure] (repeatedly 3 ids/new-node-id)
        [id initial] ((p/p:require predicate failure) (nb/install-cells [source predicate failure]))
        active (publish initial source false)
        stale (collection/content {:base false
                                   :support #{{:source source :timestamp 1 :premises-status :active}}})
        fresh (publish active source true)
        stale-predicate (update-cell fresh predicate stale)
        contradiction (collection/content
                       {:base value/contradiction
                        :support #{{:source source :timestamp 2 :premises-status :active}}})
        bad-predicate (update-cell fresh predicate contradiction)]
    (is (= :pending (:status (observation/cell-base stale-predicate failure))))
    (is (= :pending (:status (observation/cell-base bad-predicate failure))))
    (let [malformed (nb/seed-cell initial predicate 123)
          result (runner/run-network [id] malformed)]
      (is (= :failed (:status result)))
      (is (= "require expects a Boolean predicate" (ex-message (:error result)))))))

(deftest pending-constraint-transports-retraction-without-a-failure
  (let [[source predicate failure] (repeatedly 3 ids/new-node-id)
        [id initial] ((p/p:require predicate failure) (nb/install-cells [source predicate failure]))
        retracted {:source source :timestamp 2 :premises-status :retracted}
        withdrawn (update-cell initial predicate (collection/content {:premise-state #{retracted}}))
        current (net/network-cell-strongest withdrawn failure)
        replay (nb/run-propagators withdrawn [id])]
    (is (= :pending (:status (observation/base current))))
    (is (contains? (state/states-of current) retracted))
    (is (= (net/net-env withdrawn) (net/net-env replay)))))
