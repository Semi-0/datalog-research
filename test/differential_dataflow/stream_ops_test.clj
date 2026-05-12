(ns differential-dataflow.stream-ops-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as a]
            [differential-dataflow.stream-ops :as s]))

(deftest pipe-applies-fn-per-message
  (let [in  (a/chan 10)
        out ((s/pipe (fn [x] (* 2 x))) in)]
    (a/>!! in 1)
    (is (= 2 (a/<!! out)))
    (a/>!! in 7)
    (is (= 14 (a/<!! out)))
    (a/close! in)
    (is (nil? (a/<!! out)))))

(deftest scan-carries-state-and-emits-step-output
  (let [in  (a/chan 10)
        out ((s/scan 0 (fn [acc x] [(+ acc x) [acc x]])) in)]
    (a/>!! in 10)
    (is (= [0 10] (a/<!! out)))
    (a/>!! in 3)
    (is (= [10 3] (a/<!! out)))
    (a/close! in)
    (is (nil? (a/<!! out)))))

(deftest zip-aligns-two-channels
  (let [a-ch (a/chan 10)
        b-ch (a/chan 10)
        z    (s/zip a-ch b-ch 10)]
    (a/>!! a-ch :x)
    (a/>!! b-ch :y)
    (is (= [:x :y] (a/<!! z)))
    (a/close! a-ch)
    (a/close! b-ch)
    (is (nil? (a/<!! z)))))

(defn -main [& _]
  (let [{:keys [fail error pass]} (clojure.test/run-tests 'differential-dataflow.stream-ops-test)]
    (println "stream-ops-test:" pass "pass," fail "fail," error "error")
    (when (or (pos? fail) (pos? error)) (System/exit 1))))
