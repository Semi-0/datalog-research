(ns propagators.network-patch-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-patch :as patch]
            [propagators.relationship :as relationship]
            [propagators.runner :as runner]))

(deftest declaration-patch-records-only-new-topology
  (let [emitter (relationship/node-key [:outer] (new-node-id))
        input-id (new-node-id)
        output-id (new-node-id)
        propagator-id (new-node-id)
        declaration (patch/declare-propagator
                     propagator-id
                     :test/copy
                     [input-id]
                     [output-id]
                     (fn [_inputs _outputs _network] []))
        [tasks installed]
        (runner/apply-patch emitter declaration net/empty-net)
        [scheduled remaining] (tq/pop-task tasks)
        expected-children
        (set (map #(relationship/node-key [:outer] %)
                  [input-id output-id propagator-id]))
        [repeat-tasks repeated]
        (runner/apply-patch emitter declaration installed)]
    (is (= propagator-id scheduled))
    (is (tq/queue-empty? remaining))
    (is (= expected-children
           (relationship/children
            (net/net-relationship installed)
            emitter)))
    (is (tq/queue-empty? repeat-tasks))
    (is (= (net/net-relationship installed)
           (net/net-relationship repeated)))))

(deftest cell-and-unknown-patch-branches-are-explicit
  (let [cell-id (new-node-id)
        network (nb/install-cell net/empty-net cell-id)
        [tasks updated]
        (runner/apply-patch nil (message/message cell-id 42) network)]
    (testing "ordinary cell patches reuse the existing evaluator"
      (is (tq/queue-empty? tasks))
      (is (= 42 (net/network-cell-value updated cell-id))))
    (testing "the fallback rejects unknown patches"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"unknown network patch"
           (runner/apply-patch nil {:op :test/unknown} network))))))
