(ns propagators.experimental.amb-concurrent-learning-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [examples.lain.amb.concurrent-learning-checkpoint :as concurrent]
            [examples.lain.amb.learning-checkpoint-benchmark :as experiment]
            [examples.lain.amb.learning-scale-benchmark :as scale]
            [examples.lain.amb.parallel-benchmark :as profiling]
            [examples.lain.amb.search-budget :as budget]
            [examples.lain.amb.solver-lain :as lain]
            [examples.lain.amb.solver-operator :as operator]
            [examples.lain.amb.solver-strategy :as strategy]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.cells.value :as value]
            [propagators.experimental.amb.observation :as observation]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network-cache :as cache]))

(deftest sequential-and-threaded-learning-have-identical-work
  (let [fixture (experiment/prepare-fixture (scale/chain 4) :suffix)
        question (experiment/question-of (get-in fixture [:state :network]))
        pool (driver/pool 2)]
    (try
      (binding [cache/*cache* nil strategy/*executor* pool]
        (let [sequential (strategy/solve-question concurrent/sequential-solver question {:workers 2})
              threaded (strategy/solve-question concurrent/concurrent-solver question {:workers 2})
              searches (mapv experiment/search-statistics [sequential threaded])]
          (is (= (:expected fixture) (:answers sequential) (:answers threaded)))
          (is (= [[1 2] [3 4]] (:ranges threaded) (:ranges sequential)))
          (is (apply = searches))
          (is (= (mapv :nogoods (:branches sequential)) (mapv :nogoods (:branches threaded))))
          (is (= (mapv #(get-in % [:profile :metrics :activations]) (:branches sequential))
                 (mapv #(get-in % [:profile :metrics :activations]) (:branches threaded))))
          (is (= 64 (:covered-assignments (first searches))))
          (is (= 20 (:pruned-jobs (first searches))))
          (is (= 2 (count (set (map #(get-in % [:profile :thread]) (:branches threaded))))))
          (is (= 1 (count (set (map #(get-in % [:profile :thread]) (:branches sequential))))))
          (doseq [branch (:branches threaded)]
            (is (experiment/sound-nogoods? question (:nogoods branch) (:expected fixture)))
            (is (= 10 (count (:nogoods branch)))))
          ;; Solving child worlds never assigns the outer question's source cells.
          (is (every? #(value/nothing? (observation/cell-base (:network question) %))
                      (:sources question)))))
      (finally (driver/close! pool)))))

(deftest worker-count-larger-than-domain-and-empty-result
  (let [scenario {:domain [1 2] :edges #{}}
        fixture (experiment/prepare-fixture scenario :suffix)
        question (experiment/question-of (get-in fixture [:state :network]))]
    (binding [cache/*cache* nil]
      (let [result (concurrent/run question {:workers 8 :executor nil})]
        (is (= :completed (:status result)))
        (is (= [] (:answers result)))
        (is (= [[1] [2]] (:ranges result)))
        (is (= 8 (:covered-assignments (experiment/search-statistics result))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (binding [strategy/*executor* nil]
                   (strategy/solve-question concurrent/concurrent-solver question {:workers 2}))))))

(deftest concurrent-policy-swaps-through-the-existing-lain-solve
  (let [fixture (experiment/prepare-fixture (scale/chain 3) :suffix)
        pool (driver/pool 4)]
    (try
      (doseq [mode [:partitioned-learning :concurrent-learning]]
        (let [result (experiment/sample fixture mode pool)]
          (is (= (:expected fixture) (:answers result)))
          (is (= 1 (:solver-calls result)))
          (is (:sound-nogoods? result))
          (is (= 27 (get-in result [:search :covered-assignments])))))
      (finally (driver/close! pool)))))

(deftest concurrent-policy-is-a-normal-lain-binding
  (let [fixture (experiment/prepare-fixture (scale/chain 3) :suffix)
        source (str/replace (get-in fixture [:compiled :source])
                            "(solve solver" "(solve concurrent-learning-solver")
        compiled (experiment/compile-query source (:reach fixture)) pool (driver/pool 2)]
    (try
      (binding [cache/*cache* nil strategy/*executor* pool]
        (let [result (lain/run-state compiled (lain/prepare compiled))]
          (is (= :completed (:status result)))
          (is (= (:expected fixture) (:answers result)))))
      (finally (driver/close! pool)))))

(deftest seven-policies-swap-in-one-environment-without-replumbing
  (let [fixture (experiment/prepare-fixture (scale/chain 3) :suffix)
        compiled (:compiled fixture) initial (get-in fixture [:state :network])
        question (experiment/question-of initial) events (atom []) pool (driver/pool 4)]
    (try
      (binding [cache/*cache* nil strategy/*executor* pool
                operator/*observe-solve* #(swap! events conj %)]
        (let [final (reduce
                     (fn [network mode]
                       (let [result (experiment/select-and-run compiled network (get experiment/policies mode))]
                         (is (= (:expected fixture) (:answers result)))
                         (is (identical? question (:question (peek @events))))
                         (is (= (:graph initial) (:graph (:network result))))
                         (is (every? #(value/nothing? (observation/cell-base (:network result) %))
                                     (:sources question)))
                         (:network result))) initial scale/modes)
              duplicate (experiment/select-and-run compiled final concurrent/concurrent-solver)]
          (is (= 7 (count @events)))
          (is (= (:env final) (:env (:network duplicate))))))
      (finally (driver/close! pool)))))

(deftest budget-is-shared-by-nested-and-worker-profilers
  (let [scope (budget/scope {:milliseconds 10000 :activations 1})
        advance ((budget/transform scope) (fn [_ _] :advanced))
        state {:tasks (tq/into-queue [(ids/new-node-id)])}]
    (is (= :advanced (advance state {})))
    (is (thrown? clojure.lang.ExceptionInfo (advance state {})))
    (is (= 2 (:activations (budget/usage scope))))
    (is (budget/exhausted? (ex-info "wrapped" {} (ex-info "limit" {:type ::budget/exhausted})))))
  (let [fixture (experiment/prepare-fixture (scale/chain 3) :suffix)
        question (experiment/question-of (get-in fixture [:state :network]))
        scope (budget/scope {:milliseconds 10000 :activations 1}) pool (driver/pool 2)]
    (try
      (let [error (try
                    (binding [profiling/*advance-transform* (budget/transform scope)]
                      (concurrent/run question {:workers 2 :executor pool}))
                    nil
                    (catch Throwable error error))]
        (is (some? error))
        (is (budget/exhausted? error))
        (is (> (:activations (budget/usage scope)) 1)))
      (finally (driver/close! pool)))))

(deftest exhausted-budget-is-never-an-empty-complete-answer-set
  (let [fixture (experiment/prepare-fixture (scale/chain 3) :suffix)
        pool (driver/pool 2)]
    (try
      (let [row (with-redefs [scale/limits {:milliseconds 10000 :activations 1}]
                  (scale/sample fixture :concurrent-learning pool))
            summary (scale/summary [row])]
        (is (= :incomplete (:status row) (:status summary)))
        (is (= :budget-exhausted (:reason row)))
        (is (not (contains? row :answers)))
        (is (nil? (:median-ms summary))))
      (finally (driver/close! pool))))
  (is (not (budget/exhausted? (ex-info "ordinary evaluator failure" {}))))
  (is (thrown? clojure.lang.ExceptionInfo (budget/scope {:milliseconds 0 :activations 1}))))

(deftest scalable-dataflow-facts-come-from-real-installed-ports
  (let [scenario (scale/dataflow 2)]
    (is (= 9 (count (:domain scenario))))
    (is (= 10 (count (:edges scenario))))
    (is (= 3 (observation/cell-base (:subject-network scenario) (first (:subject-cells scenario)))))
    (is (= 35 (observation/cell-base (:subject-network scenario) (last (:subject-cells scenario)))))))
