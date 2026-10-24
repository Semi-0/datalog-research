(ns graph.lain-editor.experiments.stack-benchmark-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.experiments.baseline :as baseline]
            [graph.lain-editor.experiments.stack-benchmark :as benchmark]))

(deftest fixture-ledgers-distinguish-pending-and-implemented-states
  (let [pending (benchmark/fixture-instance false)
        refined (benchmark/fixture-instance true)]
    (is (= (:clients pending) (:clients refined)))
    (is (= (count (baseline/implementation-commits))
           (- (count (:commits refined)) (count (:commits pending)))))
    (is (= "(compound->stack benchmark-capture (root-compound-node benchmark-capture))"
           (:text (nth (:commits refined)
                       (+ (count (baseline/initial-commits))
                          (count (baseline/implementation-commits)))))))))

(deftest constraint-activation-classification-does-not-count-slot-plumbing
  (is (= :stack/shape
         (#'benchmark/constraint-id-for 'stack.raw:stack-shape?)))
  (is (nil? (#'benchmark/constraint-id-for
             [:compound-object/network-slot 'stack.raw:stack-shape?]))))
