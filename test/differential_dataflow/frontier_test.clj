(ns differential-dataflow.frontier-test
  (:require [clojure.test :refer [deftest is]]
            [differential-dataflow.frontier :as f]))

(deftest frontier-antichain
  (is (f/frontier? #{[0 1] [1 0]}))
  (is (f/frontier? #{[2 5] [4 1]}))
  (is (f/frontier? #{}))
  (is (f/frontier? #{[0 0]}))
  (is (not (f/frontier? #{[0 0] [0 1]})))
  (is (not (f/frontier? #{[0 0] [1 0] [0 1]}))))

(deftest frontier-merge-cases
  (is (= #{[2 5] [4 1]}
         (f/frontier-merge #{[2 5]} [4 1])))
  (is (= #{[3 6]}
         (f/frontier-merge #{[2 5]} [3 6])))
  (is (= #{[0 1] [2 0]}
         (f/frontier-merge #{[0 1] [1 0]} [2 0])))
  (is (= #{[0 1] [1 0]}
         (f/frontier-merge #{[0 1] [1 0]} [1 0])))
  (is (= #{[3 6] [4 1]}
         (f/frontier-merge #{[2 5] [4 1]} #{[3 6]})))
  (is (= #{[0 1] [1 0]}
         (f/frontier-merge #{[0 1] [1 0]} :not-a-version)))
  (is (= #{[2 5] [4 1]}
         (f/frontier-merge #{[2 5] [4 1]} #{[0 0]}))))

(deftest frontier-advanced-by
  (is (f/frontier-advanced-by? #{[2 5] [4 1]} #{[3 6] [4 1]}))
  (is (f/frontier-advanced-by? #{[0 1] [1 0]} #{[0 1] [1 0]}))
  (is (not (f/frontier-advanced-by? #{[2 5]} #{[4 1]})))
  (is (not (f/frontier-advanced-by? #{[2 5] [4 1]} #{[0 0]}))))

(defn -main [& _]
  (let [{:keys [fail error pass]} (clojure.test/run-tests 'differential-dataflow.frontier-test)]
    (println "frontier-test:" pass "pass," fail "fail," error "error")
    (when (or (pos? fail) (pos? error)) (System/exit 1))))
