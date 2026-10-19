(ns propagators.experimental.runner.amb-constraint-test
  (:require [clojure.test :refer [deftest is]]
            [experiments.amb-search.concurrent-benchmark :as inspection]
            [experiments.amb-search.constraint-fixtures :as fixtures]
            [experiments.amb-search.indexed-runner :as indexed]
            [propagators.runner :as runner]))

(deftest queens-oracle-checks-columns-and-both-diagonals
  (is (= [] (fixtures/queens-oracle 2)))
  (is (= #{[2 4 1 3] [3 1 4 2]} (set (fixtures/queens-oracle 4))))
  (is (= 10 (count (fixtures/queens-oracle 5))))
  (is (false? (fixtures/valid-queens? [1 1 3 4]))))

(deftest constrained-search-exhaustion-preserves-learning-and-repair
  (doseq [built [(fixtures/queens 2) (fixtures/decomposed-pythagorean 3)]]
    (let [ordinary (runner/completed-network
                    (runner/run-network (:tasks built) (:network built)))
          result (indexed/run built)
          actual (inspection/signature built (runner/completed-network result))]
      (is (= :completed (:status result)))
      (is (nil? (:solution actual)))
      (is (= (inspection/signature built ordinary) actual))
      (is (pos? (get-in result [:index-stats :withdrawals])))
      (is (pos? (get-in result [:index-stats :avoided-derivations]))))))
