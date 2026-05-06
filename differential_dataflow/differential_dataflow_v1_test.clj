(ns differential-dataflow.differential-dataflow-v1-test
  "Examples aligned with the Python `__main__` Collection / DifferenceSequence demo."
  (:require [clojure.test :refer [deftest is testing]]
            [differential-dataflow.differential-dataflow-v1 :as d]))

;;; Python:
;;; a = Collection([(("apple", "$5"), 3), (("banana", "$2"), 1)])
;;; b = Collection([(("apple", "$3"), 1), (("apple", "$2"), 1), (("kiwi", "$2"), 1)])
;;; trace_a = DifferenceSequence([a, Collection([(("apple", "$5"), -1), (("apple", "$7"), 1)]), Collection([(("lemon", "$1"), 1)])])
;;; trace_b = DifferenceSequence([b, Collection([]), Collection([(("lemon", "$22"), 3), (("kiwi", "$1"), 2)])])

(def ^:private a
  [[[:apple "$5"] 3] [[:banana "$2"] 1]])

(def ^:private b
  [[[:apple "$3"] 1] [[:apple "$2"] 1] [[:kiwi "$2"] 1]])

(def ^:private trace-a
  [a
   [[[:apple "$5"] -1] [[:apple "$7"] 1]]
   [[[:lemon "$1"] 1]]])

(def ^:private trace-b
  [b
   []
   [[[:lemon "$22"] 3] [[:kiwi "$1"] 2]]])

(deftest trace-map-swaps-datum-like-python
  ;; trace_a.map(lambda data: (data[1], data[0]))
  (let [out (d/trace-map (fn [[fruit price]] [price fruit]) trace-a)]
    (is (= [[["$5" :apple] 3] [["$2" :banana] 1]] (first out)))
    (is (= [[["$5" :apple] -1] [["$7" :apple] 1]] (second out)))
    (is (= [[["$1" :lemon] 1]] (nth out 2)))))

(deftest trace-filter-drops-apple-rows
  ;; trace_a.filter(lambda data: data[0] != "apple")
  (let [out (d/trace-filter (fn [[fruit _]] (not= fruit :apple)) trace-a)]
    (is (= [[[:banana "$2"] 1]] (first out)))
    (is (= [] (second out)))
    (is (= [[[:lemon "$1"] 1]] (nth out 2)))))

(deftest trace-join-matches-zip-longest-padding
  ;; trace_a.join(trace_b) — three timesteps; empty `b` middle step still participates.
  (let [j (d/trace-join trace-a trace-b)]
    (is (= 3 (count j)))
    (testing "step 0: apple rows join a's prices with b's prices"
      (is (every? #{[:apple ["$5" "$2"]] [:apple ["$5" "$3"]]}
                  (set (map first (first j)))))
      (is (= #{3} (set (map second (first j))))))
    (testing "step 2: lemon + kiwi from padded traces"
      (is (= [[[:lemon ["$1" "$22"]] 3]] (nth j 2))))))

(deftest trace-join-then-consolidate-stable
  (let [j (d/trace-join trace-a trace-b)
        c (d/trace-consolidate j)]
    ;; consolidate is per-step multiset merge; row count may match join for this fixture
    (is (= (count j) (count c)))
    (is (= (first j) (first c)))))

(deftest trace-distinct-on-string-values
  ;; trace_a.distinct() — values are price strings; min/max use numeric `<` so not used here.
  (let [out (d/trace-distinct trace-a)]
    (is (= [[[:apple "$5"] 1] [[:banana "$2"] 1]] (first out)))
    (is (= [[[:apple "$7"] 1]] (second out)))
    (is (= [[[:lemon "$1"] 1]] (nth out 2)))))

(deftest trace-distinct-rejects-nonpositive-consolidated-multiplicity
  ;; clojure.test no longer exposes thrown-with-msg? on Clojure 1.12+
  (let [msg (try (d/trace-distinct [[[[:only 1] -1]]]) nil
                 (catch Exception e (.getMessage e)))]
    (is (some? msg))
    (is (re-find #"distinct needs positive multiplicity" msg))))

(deftest trace-count-on-demo-traces
  ;; Per-key multiset cardinality at each step (sum of value-row multiplicities).
  (is (= [[[[:apple 3] 1] [[:banana 1] 1]] [] [[[:lemon 1] 1]]]
         (d/trace-count trace-a)))
  (is (= [[[[:apple 2] 1] [[:kiwi 1] 1]]
           []
           [[[:kiwi 1] -1] [[:kiwi 3] 1] [[:lemon 3] 1]]]
         (d/trace-count trace-b))))

(deftest trace-min-max-incremental-deltas
  ;; Second timestep emits no rows when extremum is unchanged (here min stays 1).
  (is (= [[[[:k 1] 1]] []]
         (d/trace-min [[[[:k 1] 2] [[:k 5] 1]] [[[:k 3] 1]]])))
  ;; Max rises from 10 → 20: retract old aggregate, publish new.
  (is (= [[[[:a 10] 1]] [[[:a 10] -1] [[:a 20] 1]]]
         (d/trace-max [[[[:a 10] 1]] [[[:a 20] 1]]]))))

(deftest trace-join-same-key-single-step
  (is (= [[[[:k [1 2]] 1]]]
         (d/trace-join [[[[:k 1] 1]]] [[[[:k 2] 1]]]))))

(deftest star-trace-reduce-agrees-with-trace-reduce
  ;; Same reducer shape as `trace-count` via `trace-reduce-rows` + unary adapter for `trace-reduce`.
  (let [count-f (fn [vals] [[(reduce (fn [acc [_ m]] (+ acc m)) 0 vals) 1]])]
    (is (= (d/trace-reduce count-f trace-a) (d/*trace-reduce count-f trace-a)))
    (is (= (d/trace-reduce count-f trace-b) (d/*trace-reduce count-f trace-b)))))

(deftest trace-min-max-on-numeric-values
  ;; Python `d` uses numeric values so min/max are meaningful (string prices would ClassCast).
  (let [trace-d [[[[:apple 11] 1] [[:apple 3] 2] [[:banana 2] 3] [[:coconut 3] 1]]]
        mn (d/trace-min trace-d)
        mx (d/trace-max trace-d)]
    (is (= [[[:apple 3] 1] [[:banana 2] 1] [[:coconut 3] 1]] (first mn)))
    (is (= [[[:apple 11] 1] [[:banana 2] 1] [[:coconut 3] 1]] (first mx)))))

(defn -main [& _]
  (let [{:keys [fail error pass]} (clojure.test/run-tests 'differential-dataflow.differential-dataflow-v1-test)]
    (println "differential-dataflow-v1-test:" pass "pass," fail "fail," error "error")
    (when (or (pos? fail) (pos? error)) (System/exit 1))))
