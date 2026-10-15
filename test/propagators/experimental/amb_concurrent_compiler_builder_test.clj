(ns propagators.experimental.amb-concurrent-compiler-builder-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.amb.concurrent-compiler-builder :as comparison]
            [examples.lain.amb.decomposed-question :as decomposed]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.cells.value :as value]
            [propagators.network :as net]))

(deftest same-complete-answers-and-leaves-across-representations
  (let [pool (driver/pool 4)]
    (try
      (doseq [[n expected] [[2 []] [5 [[3 4 5]]]]]
        (let [fixture (comparison/prepare n)]
          (doseq [mode comparison/modes]
            (let [result (comparison/checked n (comparison/sample fixture mode pool))]
              (is (= :completed (:status result)))
              (is (= expected (:answers result)))
              (is (= (* n n n) (get-in result [:metrics :leaves])))
              (is (= (min n 4) (get-in result [:metrics :execution-threads])))))))
      (finally (driver/close! pool)))))

(deftest builder-decomposition-is-explicit-and-immutable
  (let [question (decomposed/build 2) original (:network question)]
    (is (= 10 (count (decomposed/operations (:cells question)))))
    (is (= 3 (count (:sources question))))
    (is (= [1 2] (:domain question)))
    (is (every? #(value/nothing? (net/network-cell-strongest original %)) (:sources question)))
    (is (thrown? clojure.lang.ExceptionInfo (decomposed/build 0)))
    (is (thrown? clojure.lang.ExceptionInfo (comparison/sample {} :unknown nil)))))
