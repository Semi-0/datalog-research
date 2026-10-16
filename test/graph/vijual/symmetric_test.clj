(ns graph.vijual.symmetric-test
  (:require [clojure.test :refer [deftest is]]
            [graph.vijual :as v]
            [graph.vijual.layout.nodes :as nodes]
            [graph.vijual.layout.stress-preferences :as preferences]
            [graph.vijual.stress-comparison :as comparison]))

(defn objective [state]
  (let [geometry (get-in state [:numeric :geometry])
        config (preferences/prepare (:normalized state) (:edges state) (:opts state))]
    (+ (preferences/base-energy (get-in state [:topology :packed]) geometry)
       (preferences/energy config geometry))))

(defn assert-same-positions [left right]
  (is (= (mapv :id (:nodes left)) (mapv :id (:nodes right))))
  (doseq [[a b] (map vector (:nodes left) (:nodes right))
          [x y] (map vector (:position a) (:position b))]
    (is (< (Math/abs (double (- x y))) 1.0e-8))))

(deftest same-graph-shows-length-and-axis-preferences
  (let [graph (comparison/sample-graph)
        result (comparison/compare-layouts graph)
        standard (get-in result [:standard :metrics])
        symmetric (get-in result [:symmetric :metrics])]
    (is (< (:edge-length-spread-percent symmetric) (* 0.2 (:edge-length-spread-percent standard))))
    (is (< (:mean-tilt-degrees symmetric) 1.0))
    (is (> (:mean-tilt-degrees standard) 20.0))
    (is (< (:combined-objective symmetric) (:combined-objective standard)))
    (is (> (:graph-stress symmetric) (:graph-stress standard)))
    (doseq [method [:standard :symmetric]]
      (is (= (mapv #(dissoc % :position) (:nodes graph))
             (mapv #(dissoc % :position) (get-in result [method :nodes])))))
    (is (= (get-in result [:symmetric :nodes])
           (v/layout-symmetric-stress-nodes (:nodes graph) (:edges graph) (:options result))))))

(deftest standard-is-unchanged-and-preferences-can-be-disabled
  (let [{:keys [nodes edges]} (comparison/sample-graph)
        opts {:iterations 12}
        baseline (v/layout-stress-nodes nodes edges opts)]
    (is (= baseline (v/layout-stress-nodes nodes edges
                                           (assoc opts :edge-symmetry-weight 100 :edge-axis-weight 100))))
    (is (= baseline (v/layout-symmetric-stress-nodes
                    nodes edges (assoc opts :edge-symmetry-weight 0 :edge-axis-weight 0 :edge-overlap-weight 0 :mirror-symmetry-weight 0))))
    (let [state (nodes/advance-layout
                 (nodes/create-symmetric-layout nodes edges
                                                 {:edge-symmetry-weight 0 :edge-axis-weight 0 :edge-overlap-weight 0 :mirror-symmetry-weight 0})
                 {:iterations 12})]
      (is (= 0 (get-in state [:metrics :preference-edge-evaluations]))))
    (is (= (v/layout-symmetric-stress-nodes nodes edges opts)
           (v/layout-symmetric-stress-nodes nodes (into edges [["B" "A"] ["A" "A"]]) opts)))))

(deftest combined-objective-decreases-with-and-without-pins
  (let [{:keys [nodes edges]} (comparison/sample-graph)]
    (doseq [pins [#{} #{"A" "I"}]
            [length-weight axis-weight] [[6 12] [0 12] [6 0]]]
      (loop [state (v/create-symmetric-stress-layout nodes edges
                                                    {:pinned-ids pins
                                                     :edge-symmetry-weight length-weight
                                                     :edge-axis-weight axis-weight})
             remaining 40]
        (when (pos? remaining)
          (let [next-state (nodes/advance-layout state {:iterations 1})
                before (objective state) after (objective next-state)]
            (is (<= after (+ before (* 1.0e-10 (max 1.0 before)))))
            (doseq [id pins]
              (is (= (:position (first (filter #(= id (:id %)) nodes)))
                     (:position (first (filter #(= id (:id %)) (:nodes next-state)))))))
            (recur next-state (dec remaining))))))))

(deftest size-clearance-and-pin-contracts
  (let [input [{:id :a :position [0 0] :size [120 80] :label "anchor"}
               {:id :b :position [300 30] :size [120 80] :label "free"}]
        state (nodes/create-symmetric-layout input [[:a :b]] {:pinned-ids #{:a}})
        result (nodes/advance-layout state {:iterations 250})
        [x y] (:position (second (:nodes result)))
        expected (+ (Math/hypot 120 80) 10)]
    (is (= (first input) (first (:nodes result))))
    (is (< (Math/abs (- x expected)) 1.0e-6))
    (is (< (Math/abs y) 1.0e-6))
    (is (= input (:nodes state)))
    (is (= input (:nodes (nodes/advance-layout state {:iterations 0})))))
  (let [input [{:id :solo :position [2 3] :size [10 20]}]
        result (nodes/advance-layout (nodes/create-symmetric-layout input []))]
    (is (= input (:nodes result)))
    (is (= 0 (get-in result [:metrics :preference-edge-evaluations]))))
  (is (= [] (v/layout-symmetric-stress-nodes [] []))))

(deftest symmetric-sessions-keep-incremental-contracts
  (let [{:keys [nodes edges]} (comparison/sample-graph)
        state (nodes/advance-layout (nodes/create-symmetric-layout nodes edges) {:iterations 4})
        before-xs (vec (get-in state [:numeric :geometry :xs]))
        changed (assoc (first (:nodes state)) :size [160 80])
        edited (nodes/update-layout state {:upsert-nodes [changed] :pin-ids #{"I"}})
        result (nodes/advance-layout edited {:iterations 10})
        fresh (-> (nodes/create-symmetric-layout (:nodes edited) (:edges edited)
                                                 (assoc (:opts edited) :pinned-ids (:pinned-ids edited)))
                  (nodes/advance-layout {:iterations 10}))]
    (is (identical? (:topology state) (:topology edited)))
    (is (= 0 (get-in edited [:metrics :distance-sources-recomputed])))
    (assert-same-positions result fresh)
    (is (= before-xs (vec (get-in state [:numeric :geometry :xs]))))
    (is (= (* (count edges) (get-in result [:metrics :iterations]))
           (get-in result [:metrics :preference-edge-evaluations])))
    (let [topology-edit (nodes/update-layout result {:remove-edges [(first edges)]})
          advanced (nodes/advance-layout topology-edit {:iterations 3})]
      (is (= (* (count (:edges topology-edit)) (get-in advanced [:metrics :iterations]))
             (get-in advanced [:metrics :preference-edge-evaluations]))))
    (let [all-pinned (nodes/update-layout result {:pin-ids (set (map :id nodes))})
          fixed (nodes/advance-layout all-pinned)]
      (is (= (:nodes all-pinned) (:nodes fixed)))
      (is (= 0 (get-in fixed [:metrics :preference-edge-evaluations]))))))

(deftest invalid-preference-options
  (doseq [opts [{:stress-method :unknown} {:edge-symmetry-weight -1}
                {:edge-axis-weight Double/NaN} {:edge-axis-weight Double/POSITIVE_INFINITY}]]
    (is (thrown? clojure.lang.ExceptionInfo (nodes/create-layout [] [] opts)))))

(deftest balanced-stress-name-and-compatibility
  (let [{:keys [nodes edges]} (comparison/overlap-graph)
        opts {:iterations 10}
        balanced (v/layout-balanced-stress-nodes nodes edges opts)]
    (is (= balanced (v/layout-symmetric-stress-nodes nodes edges opts)))
    (is (= balanced (v/layout-stress-nodes nodes edges (assoc opts :stress-method :balanced))))
    (is (= balanced (v/layout-stress-nodes nodes edges (assoc opts :stress-method :symmetric))))
    (is (= :balanced (get-in (v/create-balanced-stress-layout nodes edges) [:opts :stress-method])))
    (is (= balanced (:nodes (v/advance-stress-layout
                             (v/create-balanced-stress-layout nodes edges opts)))))
    (is (= balanced (:nodes (v/advance-stress-layout
                             (v/create-symmetric-stress-layout nodes edges opts)))))))
