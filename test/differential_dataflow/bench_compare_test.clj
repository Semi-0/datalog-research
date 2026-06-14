(ns differential-dataflow.bench-compare-test
  (:require [clojure.test :refer [deftest is testing]]
            [bench-compare :as bench]))

(deftest chain-fact-counts-formula
  (is (= {:edges 10 :nodes 11 :path-tuples 55} (bench/chain-fact-counts 10)))
  (is (= {:edges 400 :nodes 401 :path-tuples 80200} (bench/chain-fact-counts 400))))

(deftest path-expansion-correctness
  (testing "all three strategies agree on :path support"
    (doseq [n [5 10 20]]
      (is (= {:n n :path-tuples (:path-tuples (bench/chain-fact-counts n))}
             (bench/verify-incremental-paths-agree n))))))

(deftest recorded-growing-chain-crossover
  (is (= :ok (bench/verify-recorded-crossover-conclusions!))))

(deftest recorded-delta-on-large-edb-crossover
  (is (= :ok (bench/verify-recorded-delta-crossover!))))

(deftest one-delta-on-base-agrees
  (is (= {:base-n 20 :path-tuples 231} (bench/verify-one-delta-agree 20))))

(deftest run-verification-passes
  (is (= :ok (bench/run-verification!))))
