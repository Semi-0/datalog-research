(ns differential-dataflow.multiset-test
  (:require [clojure.test :refer [deftest is testing]]
            [differential-dataflow.multiset :as ms]))

(deftest map-curried-and-full
  (let [rows [[1 2] [3 4]]]
    (is (= [[2 2] [4 4]] (ms/map inc rows)))
    (is (= [[2 2] [4 4]] ((ms/map inc) rows)))))

(deftest filter-curried-and-full
  (let [rows [[[:a 1] 1] [[:b 2] 1]]]
    (is (= [[[:a 1] 1]] (ms/filter (fn [[k _]] (= :a k)) rows)))
    (is (= [[[:a 1] 1]] ((ms/filter (fn [[k _]] (= :a k))) rows)))))

(deftest negate-append-consolidate-difference
  (is (= [[1 -2] [3 -4]] (ms/negate [[1 2] [3 4]])))
  (is (= [[1 1] [2 1] [3 1]] (ms/append [[1 1]] [[2 1] [3 1]])))
  (is (= [[1 1] [1 1] [3 1]] (ms/extend-rows [[1 1]] [[1 1] [3 1]])))
  (is (= [[:x 3]] (ms/consolidate [[:x 1] [:x 2] [:y 1] [:y -1]])))
  (is (= [[:x 1]] (ms/difference [[:x 2] [:y 1]] [[:x 1] [:y 1]]))))

(deftest group-by-key-and-reduce-by-key
  (let [rows [[[:a 1] 1] [[:a 2] 1] [[:b 10] 1]]]
    (is (= {:a [[1 1] [2 1]] :b [[10 1]]} (ms/group-by-key rows)))
    (is (= {:a 3 :b 10}
           (ms/reduce-by-key rows (fn [acc [v _]] (+ acc v)) 0)))))

(deftest fold-tally-sum
  (let [rows [[[:k 1] 2] [[:k 3] 1] [[:j 5] 1]]]
    (is (= [[[:j 1] 1] [[:k 3] 1]] (ms/tally rows)))
    (is (= [[[:j 5] 1] [[:k 5] 1]] (ms/sum rows)))))

(deftest minimum-maximum-unique
  (let [xs [[[:x 3] 1] [[:x 1] 2]]]
    (is (= [[[:x 1] 1]] (ms/minimum xs)))
    (is (= [[[:x 3] 1]] (ms/maximum xs))))
  (is (= [[[:x 1] 1] [[:x 3] 1]] (ms/unique [[[:x 1] 1] [[:x 3] 1]]))))

(deftest join-rows
  (is (= [[[:k [1 2]] 6]]
         (ms/join [[[:k 1] 2]] [[[:k 2] 3]]))))

(deftest converge-fixed-point
  (is (= [[:a 1]]
         (ms/converge (fn [c] (ms/consolidate c)) [[:a 1] [:a -1] [:a 1]]))))

(deftest minimum-rejects-nonpositive-multiplicity
  (is (thrown? Exception (ms/minimum [[[:x 1] -1]]))))
