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

(deftest scan-emit-can-emit-zero-or-many-messages
  (let [in (a/chan 10)
        out ((s/scan-emit
              0
              (fn [acc x]
                [(+ acc x)
                 (when (pos? x)
                   [acc (+ acc x)])]))
             in)]
    (a/>!! in 2)
    (is (= 0 (a/<!! out)))
    (is (= 2 (a/<!! out)))
    (a/>!! in -1)
    (is (= ::none (a/alt!! out ([v] v) (a/timeout 50) ::none)))
    (a/close! in)
    (is (nil? (a/<!! out)))))

(deftest merge-tagged-identifies-source-channel
  (let [a-ch (a/chan 10)
        b-ch (a/chan 10)
        out (s/merge-tagged [[:a a-ch] [:b b-ch]] 10)]
    (a/>!! a-ch :x)
    (is (= [:a :x] (a/<!! out)))
    (a/>!! b-ch :y)
    (is (= [:b :y] (a/<!! out)))
    (a/close! a-ch)
    (a/close! b-ch)
    (is (nil? (a/<!! out)))))

(deftest pipe-to-copies-and-closes-destination
  (let [source (a/chan 10)
        dest (a/chan 10)]
    (s/pipe-to! source dest)
    (a/>!! source :x)
    (is (= :x (a/<!! dest)))
    (a/close! source)
    (is (nil? (a/<!! dest)))))
