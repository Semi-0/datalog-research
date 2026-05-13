(ns differential-dataflow.graph-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as a]
            [differential-dataflow.graph.interface :as g]
            [differential-dataflow.multiset :as ms]))

(defn- drain-close!
  "Close `in-ch` after optional puts; read one batch from `out-ch` (blocking)."
  [out-ch in-ch]
  (a/close! in-ch)
  (a/<!! out-ch))

(deftest graph-map-through-channel
  (let [in (a/chan 10)
        out ((g/map inc) in)]
    (a/>!! in [[1 2] [3 4]])
    (is (= [[2 2] [4 4]] (a/<!! out)))
    (is (nil? (drain-close! out in)))))

(deftest graph-filter-through-channel
  (let [in (a/chan 10)
        out ((g/filter (fn [d] (even? d))) in)]
    (a/>!! in [[2 1] [3 1] [4 1]])
    (is (= [[2 1] [4 1]] (a/<!! out)))
    (is (nil? (drain-close! out in)))))

(deftest graph-negate-through-channel
  (let [in (a/chan 10)
        out (g/negate in)]
    (a/>!! in [[1 2] [3 -1]])
    (is (= [[1 -2] [3 1]] (a/<!! out)))
    (is (nil? (drain-close! out in)))))

(deftest graph-concat-zips-and-appends
  (let [a-ch (a/chan 10)
        b-ch (a/chan 10)
        out (g/concat a-ch b-ch)]
    (a/>!! a-ch [[1 1] [2 1]])
    (a/>!! b-ch [[10 1]])
    (is (= [[1 1] [2 1] [10 1]] (a/<!! out)))
    (a/close! a-ch)
    (a/close! b-ch)
    (is (nil? (a/<!! out)))))

(deftest graph-join-one-step
  (let [a-ch (a/chan 10)
        b-ch (a/chan 10)
        out (g/join a-ch b-ch 32)]
    (a/>!! a-ch [[[:k 1] 1]])
    (a/>!! b-ch [[[:k 2] 1]])
    (is (= [[[:k [1 2]] 1]] (a/<!! out)))
    (a/close! a-ch)
    (a/close! b-ch)
    (is (nil? (a/<!! out)))))

(deftest graph-count-first-batch
  (let [c (a/chan 10)
        out ((g/count) c)]
    (a/>!! c [[[:x :a] 2] [[:x :b] 1]])
    (is (= [[[:x 3] 1]] (a/<!! out)))
    (is (nil? (drain-close! out c)))))

(deftest graph-count-second-batch-emits-delta
  (let [c (a/chan 10)
        out ((g/count) c)]
    (a/>!! c [[[:x :a] 2]])
    (is (= [[[:x 2] 1]] (a/<!! out)))
    (a/>!! c [[[:x :b] 1]])
    (let [second-batch (a/<!! out)]
      (is (= #{[[:x 2] -1] [[:x 3] 1]} (set second-batch))))
    (is (nil? (drain-close! out c)))))

(deftest graph-reduce-consolidated-bag
  ;; `f` returns multiset rows per key; first step emits full bag vs empty prior output.
  (let [c (a/chan 10)
        out ((g/reduce (fn [vals] (ms/consolidate vals))) c)]
    (a/>!! c [[[:p :q] 1] [[:p :r] 1]])
    (is (= (sort-by (fn [[[_ v] _]] v) [[[:p :q] 1] [[:p :r] 1]])
           (sort-by (fn [[[_ v] _]] v) (a/<!! out))))
    (is (nil? (drain-close! out c)))))

(deftest graph-reduce-pipes-to-negate
  (let [c (a/chan 10)
        out (g/negate ((g/reduce (fn [vals] (ms/consolidate vals))) c))]
    (a/>!! c [[[:p :q] 1] [[:p :r] 1]])
    (is (= #{[[:p :q] -1] [[:p :r] -1]} (set (a/<!! out))))
    (is (nil? (drain-close! out c)))))

(deftest graph-reduce-explicit-buf-still-curried
  (let [c (a/chan 10)
        out ((g/reduce (fn [vals] (ms/consolidate vals)) 32) c)]
    (a/>!! c [[[:p :q] 1]])
    (is (= [[[:p :q] 1]] (a/<!! out)))
    (is (nil? (drain-close! out c)))))

(deftest graph-count-explicit-buf-still-curried
  (let [c (a/chan 10)
        out ((g/count 32) c)]
    (a/>!! c [[[:x :a] 1]])
    (is (= [[[:x 1] 1]] (a/<!! out)))
    (is (nil? (drain-close! out c)))))

(defn -main [& _]
  (let [{:keys [fail error pass]} (clojure.test/run-tests 'differential-dataflow.graph-test)]
    (println "graph-test:" pass "pass," fail "fail," error "error")
    (when (or (pos? fail) (pos? error)) (System/exit 1))))
