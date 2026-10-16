(ns graph.vijual.clearance-test
  (:require [clojure.test :refer [deftest is]]
            [graph.vijual.layout.nodes :as nodes]
            [graph.vijual.layout.stress-conflicts :as conflicts]
            [graph.vijual.stress-comparison :as comparison]))

(deftest segment-geometry-boundaries
  (is (conflicts/crossing? [0 0] [10 10] [0 10] [10 0]))
  (is (not (conflicts/crossing? [0 0] [10 0] [10 0] [10 10])))
  (is (= 5.0 (conflicts/overlap-length [0 0] [10 0] [5 0] [15 0])))
  (is (= 5.0 (conflicts/overlap-length [0 10] [0 0] [0 5] [0 15])))
  (is (= 0.0 (conflicts/overlap-length [0 0] [10 0] [10 0] [20 0])))
  (is (= 0.0 (conflicts/overlap-length [0 0] [10 0] [0 1] [10 1])))
  (is (conflicts/segment-hits-box? [-20 0] [20 0] [0 0] 5 5))
  (is (conflicts/segment-hits-box? [-20 -20] [20 20] [0 0] 5 5))
  (is (not (conflicts/segment-hits-box? [-20 5] [20 5] [0 0] 5 5)))
  (is (not (conflicts/segment-hits-box? [-20 6] [20 6] [0 0] 5 5)))
  (is (= {:t 0.0 :point [0.0 0.0] :distance 5.0}
         (conflicts/closest-point [3 4] [0 0] [0 0]))))

(deftest collinear-overlap-example
  (let [result (comparison/compare-layouts (comparison/overlap-graph))
        before (get-in result [:standard :metrics]) after (get-in result [:symmetric :metrics])]
    (is (= 6 (:edge-overlaps before)))
    (is (pos? (:edge-node-overlaps before)))
    (is (pos? (:node-overlaps before)))
    (is (= 0 (:edge-overlaps after)))
    (is (= 0 (:edge-crossings after)))
    (is (= 0 (:edge-node-overlaps after)))
    (is (= 0 (:node-overlaps after)))
    (is (< (:combined-objective after) (:combined-objective before)))
    (is (= (mapv #(dissoc % :position) (:input result))
           (mapv #(dissoc % :position) (get-in result [:symmetric :nodes]))))))

(deftest unrelated-node-clears-pinned-edge-without-graph-force
  (let [input [{:id :a :position [-100 0] :size [20 20]}
               {:id :b :position [100 0] :size [20 20]}
               {:id :node :position [0 0] :size [40 30]}]
        state (nodes/create-symmetric-layout input [[:a :b]]
                                             {:pinned-ids #{:a :b} :disconnected-weight 0})
        before (comparison/summary state)
        result (nodes/advance-layout state {:iterations 40})
        after (comparison/summary result)]
    (is (= 1 (:edge-node-overlaps before)))
    (is (= 0 (:edge-node-overlaps after)))
    (is (= 0 (:node-edge-conflicts after)))
    (is (= (subvec input 0 2) (subvec (:nodes result) 0 2)))
    (is (not= (:position (last input)) (:position (last (:nodes result)))))
    (is (= input (:nodes state)))
    (is (pos? (get-in result [:metrics :overlap-corrections])))))

(deftest crossing-edges-and-impossible-pins
  (let [input [{:id :a :position [-80 -80] :size [20 20]}
               {:id :b :position [80 80] :size [20 20]}
               {:id :c :position [-80 80] :size [20 20]}
               {:id :d :position [80 -80] :size [20 20]}]
        state (nodes/create-symmetric-layout input [[:a :b] [:c :d]]
                                             {:edge-overlap-weight 100 :disconnected-weight 0})
        result (nodes/advance-layout state {:iterations 100})
        frozen (nodes/update-layout state {:pin-ids #{:a :b :c :d}})
        pinned (nodes/advance-layout frozen)]
    (is (= 1 (:edge-crossings (comparison/summary state))))
    (is (= 0 (:edge-crossings (comparison/summary result))))
    (is (= input (:nodes pinned)))
    (is (= 1 (get-in pinned [:metrics :edge-crossings])))
    (is (= 0 (get-in pinned [:metrics :iterations])))))

(deftest legitimate-chain-junction-is-not-an-edge-overlap
  (let [state (nodes/create-symmetric-layout
               [{:id :a :position [0 0] :size [10 10]}
                {:id :b :position [100 0] :size [10 10]}
                {:id :c :position [200 0] :size [10 10]}] [[:a :b] [:b :c]])
        metrics (comparison/summary state)]
    (is (= 0 (:edge-overlaps metrics)))
    (is (= 0 (:edge-crossings metrics)))
    (is (= 0 (:edge-node-overlaps metrics)))))

(deftest invalid-clearance-settings
  (doseq [opts [{:edge-clearance -1} {:edge-overlap-weight -1}
                {:edge-clearance Double/NaN} {:edge-overlap-weight Double/POSITIVE_INFINITY}]]
    (is (thrown? clojure.lang.ExceptionInfo (nodes/create-layout [] [] opts)))))
