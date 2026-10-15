(ns graph.vijual.flow-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as async]
            [graph.vijual.layout.nodes :as nodes]))

(defn receive! [channel]
  (let [[value port] (async/alts!! [channel (async/timeout 2000)])]
    (is (= port channel) "Layout stream must respond or close without hanging")
    value))

(deftest layout-stream-recovers-and-closes
  (let [input-nodes [{:id :a :position [0 0] :size [10 10]}
                     {:id :b :position [200 0] :size [20 20]}]
        state (nodes/create-layout input-nodes [[:a :b]])
        input (async/chan 2)
        output ((nodes/layout-flow state 2) input)]
    (async/>!! input {:changes {}})
    (let [result (receive! output)]
      (is (= [] (:deltas result)))
      (is (= input-nodes (:nodes result)))
      (is (= 0 (get-in result [:metrics :pair-evaluations]))))
    (async/>!! input {:changes {:add-edges [[:a :missing]]}})
    (is (some? (:error (receive! output))))
    (async/>!! input {:changes {:upsert-nodes [(assoc (first input-nodes) :label "changed")]}
                      :iterations 2})
    (let [result (receive! output)
          expected (-> state
                       (nodes/update-layout {:upsert-nodes [(assoc (first input-nodes) :label "changed")]})
                       (nodes/advance-layout {:iterations 2}))]
      (is (= (:nodes expected) (:nodes result)))
      (is (= 4 (count (:deltas result))))
      (is (= 0 (get-in result [:metrics :distance-sources-recomputed]))))
    (async/close! input)
    (is (nil? (receive! output)))
    (is (= input-nodes (:nodes state)))))
