(ns propagators.experimental.amb-parallel-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.checkpoint-question :as question]
            [examples.lain.amb.domain-refinement :as domain]
            [examples.lain.amb.range-search :as search]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.experimental.amb.observation :as observation]
            [propagators.network :as net]
            [propagators.propagator :as prop])
  (:import [java.util.concurrent CountDownLatch RejectedExecutionException TimeUnit]))

(def ^:dynamic *token* nil)

(deftest partitions-cover-the-range-once
  (doseq [n [1 4 5 8 12] workers [1 2 4 16]]
    (let [values (vec (range 1 (inc n))) parts (search/partition-ranges values workers)]
      (is (= values (vec (mapcat identity parts))))
      (is (= (min n workers) (count parts)))
      (is (every? seq parts))
      (is (<= (- (apply max (map count parts)) (apply min (map count parts))) 1))))
  (doseq [[values workers] [[[] 1] [[1 1] 2] [[1 2] 0]]]
    (is (thrown? clojure.lang.ExceptionInfo (search/partition-ranges values workers)))))

(deftest driver-uses-concurrent-workers-and-preserves-bindings
  (let [pool (driver/pool 2) entered (CountDownLatch. 2)]
    (try
      (let [results (binding [*token* :captured]
                      (driver/dispatch
                       pool [1 2]
                       (fn [v]
                         (.countDown entered)
                         (if (.await entered 5 TimeUnit/SECONDS)
                           {:value v :token *token* :thread (.getName (Thread/currentThread))}
                           (throw (ex-info "Workers did not overlap" {}))))))]
        (is (= [1 2] (mapv :value results)))
        (is (= [:captured :captured] (mapv :token results)))
        (is (= 2 (count (set (map :thread results)))))
        (is (= [] (driver/dispatch pool [] identity))))
      (finally (driver/close! pool)))
    (is (.isTerminated pool))))

(deftest worker-errors-and-rejection-stay-errors
  (let [pool (driver/pool 2) error (ex-info "worker failed" {:test true})]
    (try
      (is (identical? error (try (driver/dispatch pool [1 2] (fn [_] (throw error)))
                                (catch Throwable caught caught))))
      (finally (driver/close! pool)))
    (is (thrown? RejectedExecutionException (driver/dispatch pool [1] identity)))
    (is (thrown? clojure.lang.ExceptionInfo (driver/pool 0)))))

(deftest workers-reuse-exact-topology-and-isolate-values
  (doseq [build [question/build domain/build]]
    (let [question (build 5) template (search/template question)
          parts (search/partition-ranges (:domain question) 2)
          states (search/fork-states template parts)
          built (:built template) original (:network built)
          original-frontier (observation/cell-base original (:frontier built))
          frontiers (mapv #(observation/cell-base (:network %) (:frontier built)) states)
          child-states (mapv :state (mapcat :pending frontiers))]
      (doseq [state states]
        (is (= (net/net-graph original) (net/net-graph (:network state))))
        (is (= (set (keys (net/net-env original))) (set (keys (net/net-env (:network state))))))
        (doseq [[id entry] (net/net-env original) :when (fn? (:activate entry))]
          (is (identical? (:activate entry) (:activate (net/network-env-lookup (:network state) id))))))
      (is (apply = (map #(net/net-graph (:network %)) child-states)))
      (is (apply = (map #(set (keys (net/net-env (:network %)))) child-states)))
      (doseq [child child-states [id entry] (net/net-env (:network question)) :when (prop/prop? entry)]
        (is (identical? (prop/prop-f entry) (prop/prop-f (net/network-env-lookup (:network child) id)))))
      (if (= :domain (:kind question))
        (doseq [[values frontier] (map vector parts frontiers)]
          (let [child (:network (:state (first (:pending frontier))))]
            (is (= (set values) (domain/candidates
                                (net/network-cell-strongest child (first (:sources question))))))
            (is (= (net/net-graph (:network question)) (net/net-graph child)))))
        (doseq [[values frontier] (map vector parts frontiers)]
          (is (= values (mapv :value (reverse (:pending frontier)))))
          (is (= #{(first (:sources question))} (set (map :source (:pending frontier)))))))
      (is (not= (:network (first states)) (:network (second states))))
      (is (= original-frontier (observation/cell-base original (:frontier built))))
      (let [pool (driver/pool 2)]
        (try
          (let [result (search/run template 2 pool)]
            (is (= [3 4 5] (:solution result)))
            (is (= original-frontier (observation/cell-base original (:frontier built)))))
          (finally (driver/close! pool)))))))

(deftest sequential-and-threaded-answers-match-previous-question
  (let [pool (driver/pool 4)]
    (try
      (doseq [[build bounds] [[question/build [4 5 8]] [domain/build [4 5 8 12]]]
              n bounds]
        (let [template (search/template (build n))
              sequential (search/run template 4 nil)
              threaded (search/run template 4 pool)
              expected (:solution (oracle/chronological n))]
          (is (= expected (:solution sequential) (:solution threaded)))
          (is (= (:status sequential) (:status threaded)))
          (is (= (if expected :solution :exhausted) (:status threaded)))
          (is (= (mapv #(select-keys % [:status :solution]) (:branches sequential))
                 (mapv #(select-keys % [:status :solution]) (:branches threaded))))))
      (finally (driver/close! pool)))))

(deftest network-evaluation-errors-do-not-become-exhaustion
  (let [pool (driver/pool 2) original (ex-info "constraint failed" {:test true})]
    (try
      (doseq [build [question/build domain/build]]
        (let [template (search/template (build 5 (fn [_ _ _] (throw original))))
              error (try (search/run template 2 pool) nil (catch Throwable caught caught))]
          (is (instance? Throwable error))
          (is (some #(identical? original %) (take-while some? (iterate ex-cause error))))))
      (finally (driver/close! pool)))))

(deftest invalid-worker-results-are-not-dead-ends
  (is (thrown? clojure.lang.ExceptionInfo (search/select-answer [])))
  (is (thrown? clojure.lang.ExceptionInfo (search/select-answer [{:status :failed}])))
  (is (thrown? clojure.lang.ExceptionInfo (search/select-answer [{:status :solution :solution nil}]))))
