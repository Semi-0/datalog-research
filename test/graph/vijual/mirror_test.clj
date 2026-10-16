(ns graph.vijual.mirror-test
  (:require [clojure.test :refer [deftest is]]
            [graph.vijual.layout.nodes :as nodes]
            [graph.vijual.layout.stress-preferences :as preferences]
            [graph.vijual.layout.stress-symmetry :as symmetry]
            [graph.vijual.stress-comparison :as comparison]))

(defn- config [state]
  (preferences/prepare (:normalized state) (:edges state) (:opts state)))

(defn- objective [state]
  (let [geometry (get-in state [:numeric :geometry])]
    (+ (preferences/base-energy (get-in state [:topology :packed]) geometry)
       (preferences/energy (config state) geometry))))

(defn- create [opts]
  (let [{:keys [nodes edges]} (comparison/overlap-graph)]
    (nodes/create-symmetric-layout nodes edges (merge {:edge-length 100 :gap 12} opts))))

(deftest conservative-twin-inference
  (let [state (create {}) pairs (:mirrors (config state))]
    (is (= [{:a 0 :b 2 :anchors [1 3]} {:a 1 :b 3 :anchors [0 2]}] pairs))
    (let [unequal (nodes/update-layout state
                                       {:upsert-nodes [(assoc (first (:nodes state)) :size [53 32])]})]
      (is (= [{:a 1 :b 3 :anchors [0 2]}] (:mirrors (config unequal)))))
    (let [asymmetric (nodes/update-layout state {:remove-edges [["A" "B"]]})]
      (is (empty? (:mirrors (config asymmetric)))))
    (is (empty? (:mirrors (config (create {:mirror-symmetry-weight 0}))))))
  ;; A clique has larger equivalence classes; no arbitrary pairing is inferred.
  (is (empty? (symmetry/prepare
               [{:id :a :size [10 10]} {:id :b :size [10 10]} {:id :c :size [10 10]}]
               [[:a :b] [:b :c] [:c :a]]))))

(deftest mirrored-example-escapes-collinear-trap
  (let [initial (create {})
        before (vec (get-in initial [:numeric :geometry :xs]))
        result (nodes/advance-layout initial {:iterations 250})
        metrics (comparison/summary result)
        old-method (nodes/advance-layout (create {:mirror-symmetry-weight 0}) {:iterations 250})]
    (is (< (:mirror-error metrics) 1.0e-7))
    (is (= 2 (:mirror-pairs metrics)))
    (is (< (objective result) (* 0.9 (objective old-method))))
    (doseq [key [:edge-overlaps :edge-crossings :edge-node-overlaps :node-overlaps :node-edge-conflicts]]
      (is (zero? (get metrics key))))
    (is (pos? (get-in result [:metrics :symmetry-corrections])))
    (is (pos? (get-in result [:metrics :symmetry-proposals])))
    (is (= before (vec (get-in initial [:numeric :geometry :xs]))))
    (is (= (:nodes initial) (:nodes (nodes/advance-layout initial {:iterations 0}))))))

(deftest full-objective-descends-and-pins-survive
  (doseq [pins [#{} #{"A"} #{"A" "C"} #{"A" "B" "C" "D"}]
          overlap-weight [0 24]]
    (loop [state (create {:pinned-ids pins :edge-overlap-weight overlap-weight}) remaining 20]
      (when (pos? remaining)
        (let [next-state (nodes/advance-layout state {:iterations 1})
              before (objective state) after (objective next-state)]
          (is (<= after (+ before (* 1.0e-10 (max 1.0 before)))))
          (doseq [id pins]
            (is (= (:position (first (filter #(= id (:id %)) (:nodes state))))
                   (:position (first (filter #(= id (:id %)) (:nodes next-state)))))))
          (recur next-state (dec remaining)))))))

(deftest mirror-pairs-are-recomputed-after-edits
  (let [settled (nodes/advance-layout (create {}) {:iterations 5})
        edited (nodes/update-layout settled {:remove-edges [["A" "B"]] :pin-ids #{"D"}})
        result (nodes/advance-layout edited {:iterations 5})
        fresh (-> (nodes/create-symmetric-layout (:nodes edited) (:edges edited)
                                                  (assoc (:opts edited) :pinned-ids (:pinned-ids edited)))
                  (nodes/advance-layout {:iterations 5}))]
    (is (= (:nodes fresh) (:nodes result)))
    (is (empty? (:mirrors (config edited)))))
  (let [coincident (nodes/update-layout (create {})
                                       {:upsert-nodes (mapv #(assoc % :position [0 0])
                                                            (:nodes (create {})))})
        result (nodes/advance-layout coincident {:iterations 5})]
    (is (every? #(Double/isFinite (double %)) (mapcat :position (:nodes result))))))

(deftest chunked-relaxation-and-resizing-match-fresh-sessions
  (let [initial (create {})
        chunked (-> initial (nodes/advance-layout {:iterations 10})
                    (nodes/advance-layout {:iterations 10}))
        whole (nodes/advance-layout initial {:iterations 20})
        edited (nodes/update-layout chunked
                                    {:upsert-nodes [(assoc (first (:nodes chunked)) :size [80 40])]})
        fresh (nodes/create-symmetric-layout (:nodes edited) (:edges edited) (:opts edited))]
    (is (= (:nodes whole) (:nodes chunked)))
    (is (identical? (:topology chunked) (:topology edited)))
    (is (= 0 (get-in edited [:metrics :distance-sources-recomputed])))
    (is (= (:nodes (nodes/advance-layout fresh {:iterations 10}))
           (:nodes (nodes/advance-layout edited {:iterations 10}))))))

(deftest invalid-mirror-weight
  (doseq [value [-1 Double/NaN Double/POSITIVE_INFINITY]]
    (is (thrown? clojure.lang.ExceptionInfo (create {:mirror-symmetry-weight value})))))
