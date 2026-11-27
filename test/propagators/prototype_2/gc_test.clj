(ns propagators.prototype-2.gc-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.prototype-2.core :as p2]
            [propagators.prototype-2.gc :as gc]
            [propagators.prototype-2.runner :as runner]))

(deftest mark-sweep-id-chain-retains-ends
  (testing "completed mid-chain cells and props sweep; retained ends keep values"
    (let [cells [:c0 :c1 :c2 :c3 :c4 :c5]
          network (p2/install-id-chain p2/empty-network cells)
          [_ network] (p2/tell network :c0 42)
          network (p2/run-tasks network)
          marked (gc/mark-completed network #{:c0 :c5})
          swept (gc/sweep network marked)]
      (is (every? #(contains? marked %) [:c1 :c2 :c3 :c4]))
      (is (not (contains? marked :c0)))
      (is (not (contains? marked :c5)))
      (is (pos? (count (filter p2/propagator-f?
                               (vals (p2/network-value network))))))
      (is (empty? (filter p2/propagator-value?
                          (vals (p2/network-value swept)))))
      (is (= 42 (p2/cell-value swept :c0)))
      (is (= 42 (p2/cell-value swept :c5)))
      (is (p2/nothing? (p2/cell-value swept :c2))))))

(deftest collect-independent-of-runner
  (testing "gc/collect alone matches mark+sweep"
    (let [cells [:c0 :c1 :c2]
          network (p2/install-id-chain p2/empty-network cells)
          [_ network] (p2/tell network :c0 7)
          network (p2/run-tasks network)
          via-collect (gc/collect network #{:c2})
          via-parts (gc/sweep network (gc/mark-completed network #{:c2}))]
      (is (= (p2/network-value via-collect) (p2/network-value via-parts)))
      (is (= 7 (p2/cell-value via-collect :c2)))
      (is (p2/nothing? (p2/cell-value via-collect :c0))))))

(deftest runner-done-transform-gc
  (testing "run-tasks-gc sweeps at quiescence via constructor done-transform"
    (let [cells [:c0 :c1 :c2 :c3 :c4 :c5]
          network (p2/install-id-chain p2/empty-network cells)
          [_ network] (p2/tell network :c0 99)
          swept (runner/run-tasks-gc network #{:c5})]
      (is (= 99 (p2/cell-value swept :c5)))
      (is (empty? (filter p2/propagator-value?
                          (vals (p2/network-value swept)))))
      (is (p2/nothing? (p2/cell-value swept :c0)))
      (is (p2/nothing? (p2/cell-value swept :c3))))))

(deftest run-tasks-without-gc-preserves-topology
  (testing "default runner leaves completed props in place"
    (let [cells [:c0 :c1 :c2]
          network (p2/install-id-chain p2/empty-network cells)
          [_ network] (p2/tell network :c0 1)
          network (p2/run-tasks network)
          props (into []
                      (keep (fn [[id v]]
                              (when (p2/propagator-value? v) id)))
                      (p2/network-value network))]
      (is (= 2 (count props)))
      (is (= 1 (p2/cell-value network :c2))))))
