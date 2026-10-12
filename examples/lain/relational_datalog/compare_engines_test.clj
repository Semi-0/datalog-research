(ns examples.lain.relational-datalog.compare-engines-test
  (:require [clojure.edn :as edn]
            [clojure.test :as test :refer [deftest is]]
            [examples.lain.relational-datalog.compare-engines :as comparison]
            [leapfrog :as leapfrog]
            [leapfrog-pure :as pure]))

(deftest existing-set-engines-match-every-fixture
  (doseq [fixture comparison/fixtures engine [:leapfrog :leapfrog-pure]]
    (let [state (reduce #(comparison/advance engine %1 %2) {} (:batches fixture))]
      (is (= (comparison/expected-facts (:batches fixture)) (comparison/read-facts engine state)))
      (is (= (comparison/read-facts engine state)
             (comparison/read-facts engine (comparison/advance engine state
                                                               (get (comparison/read-facts engine state) :edge))))))))

(deftest differential-visible-support-matches-acyclic-fixtures
  (doseq [fixture (filter #(comparison/comparable? :differential %) comparison/fixtures)]
    (let [state (reduce #(comparison/advance :differential %1 %2)
                        (comparison/initial-state :differential) (:batches fixture))]
      (is (= (comparison/expected-facts (:batches fixture))
             (comparison/read-facts :differential state))))))

(deftest delta-adapter-agrees-with-full-engine-evaluation
  (doseq [[engine evaluate] [[:leapfrog leapfrog/semi-naive] [:leapfrog-pure pure/semi-naive]]
          fixture comparison/fixtures]
    (let [edges (:edge (comparison/expected-facts (:batches fixture)))
          full (evaluate {:edge edges} comparison/rules)
          incremental (reduce #(comparison/advance engine %1 %2) {} (:batches fixture))]
      (is (= (comparison/read-facts engine full) (comparison/read-facts engine incremental))))))

(deftest comparison-boundaries
  (is (false? (comparison/comparable? :differential {:name :cycle})))
  (is (true? (comparison/comparable? :leapfrog {:name :cycle})))
  (is (thrown? clojure.lang.ExceptionInfo (comparison/initial-state :unknown)))
  (is (thrown? clojure.lang.ExceptionInfo (comparison/advance :unknown {} #{})))
  (is (thrown? clojure.lang.ExceptionInfo (comparison/read-facts :unknown {})))
  (is (thrown? clojure.lang.ExceptionInfo (comparison/repetitions :unknown))))

(deftest recorded-comparison-is-valid-and-corresponds-to-fixtures
  (let [records (edn/read-string
                 (slurp "examples/lain/relational_datalog/COMPARISON_RESULTS.edn"))]
    (is (= (set comparison/engines) (set (keys (:initialization-median-ms (first records))))))
    (doseq [record (filter :case records)
            result (if (:results record) (:results record) [record])]
      (let [fixture (first (filter #(= (:case record) (:name %)) comparison/fixtures))
            expected (update-vals (comparison/expected-facts (:batches fixture)) count)
            counts (if (map? (:counts result)) [(:counts result)] (:counts result))]
        (is (= 7 (:samples result)))
        (is (<= (:min-ms result) (:median-ms result) (:max-ms result)))
        (is (every? #(= expected %) counts))))))

(defn -main [& args]
  (if (empty? args)
    (let [result (test/run-tests 'examples.lain.relational-datalog.compare-engines-test)]
      (shutdown-agents)
      (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))
    (throw (ex-info "Comparison test takes no arguments" {:args args}))))
