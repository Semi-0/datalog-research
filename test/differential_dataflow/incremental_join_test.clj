(ns differential-dataflow.incremental-join-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as async]
            [differential-dataflow.graph.interface :as graph]
            [differential-dataflow.index :as index]))

(defn receive! [channel]
  (let [[value port] (async/alts!! [channel (async/timeout 2000)])]
    (is (= channel port) "Join must respond without hanging")
    value))

(deftest join-replacements-retractions-and-empty-batches
  (let [left (async/chan 2) right (async/chan 2) output (graph/join left right)
        send! (fn [a b]
                (async/>!! left a) (async/>!! right b)
                (set (receive! output)))]
    (is (= #{[[:a [1 10]] 1] [[:b [2 20]] 1]}
           (send! [[[:a 1] 2] [[:a 1] -1] [[:b 2] 1]]
                  [[[:a 10] 1] [[:b 20] 1]])))
    (is (= #{[[:a [1 10]] -1] [[:a [3 30]] 1]}
           (send! [[[:a 1] -1] [[:a 3] 1]] [[[:a 10] -1] [[:a 30] 1]])))
    (is (= #{} (send! [] [])))
    (is (= #{[[:a [3 30]] -1]} (send! [[[:a 3] -1]] [])))
    (is (= #{[[:a [3 40]] 1]} (send! [[[:a 3] 1]] [[[:a 30] -1] [[:a 40] 1]])))
    (is (= #{[[:b [2 20]] -1]} (send! [] [[[:b 20] -1]])))
    (async/close! left) (async/close! right)
    (is (nil? (receive! output)))))

(deftest join-consolidates-only-touched-keys
  (let [compact index/compact-keys calls (atom [])]
    (with-redefs [index/compact-keys (fn [idx keys]
                                     (swap! calls conj (set keys))
                                     (compact idx keys))]
      (let [left (async/chan 2) right (async/chan 2) output (graph/join left right)]
        (async/>!! left [[[:a 1] 1] [[:b 2] 1]])
        (async/>!! right [[[:a 10] 1] [[:b 20] 1]])
        (receive! output)
        (reset! calls [])
        (async/>!! left [[[:a 1] -1] [[:a 3] 1]])
        (async/>!! right [])
        (receive! output)
        (is (= [#{:a} #{}] @calls))
        (reset! calls [])
        (async/>!! left []) (async/>!! right [])
        (is (= [] (receive! output)))
        (is (= [#{} #{}] @calls))
        (async/close! left) (async/close! right)
        (is (nil? (receive! output)))))))
