(ns graph.vijual.stress-comparison
  "Reproducible comparison: identical graph, geometry, sizes and iteration budget.
  Run -M -m graph.vijual.stress-comparison [output.json] for actual solver output."
  (:require [graph.json :as json]
            [graph.vijual.layout.nodes :as nodes]
            [graph.vijual.layout.stress-conflicts :as conflicts]
            [graph.vijual.layout.stress-preferences :as preferences]
            [graph.vijual.layout.stress-symmetry :as symmetry]))

(defn sample-graph []
  (let [angle (* Math/PI (/ 23.0 180.0))]
    {:title "Nine-node grid"
     :nodes (mapv (fn [i]
                    (let [x (* 115.0 (mod i 3)) y (* 90.0 (quot i 3))
                          dx (* 14.0 (Math/sin (* i 1.7)))
                          dy (* 12.0 (Math/cos (* i 2.3)))]
                      {:id (str (char (+ 65 i))) :size [36 24]
                       :position [(+ (- (* x (Math/cos angle)) (* y (Math/sin angle))) dx)
                                  (+ (* x (Math/sin angle)) (* y (Math/cos angle)) dy)]}))
                  (range 9))
     :edges (mapv (fn [[a b]] [(str (char (+ 65 a))) (str (char (+ 65 b)))])
                  (concat (for [row (range 3) column (range 2)]
                            [(+ (* row 3) column) (+ (* row 3) column 1)])
                          (for [row (range 2) column (range 3)]
                            [(+ (* row 3) column) (+ (* (inc row) 3) column)])))}))

(defn overlap-graph []
  {:title "Collinear diamond"
   :nodes (mapv (fn [id x] {:id id :position [x 0] :size [52 32]})
                ["A" "B" "C" "D"] [0 110 220 330])
   :edges [["A" "B"] ["B" "C"] ["C" "D"] ["D" "A"] ["A" "C"]]})

(defn summary [state]
  (let [positions (into {} (map (juxt :id :position) (:normalized state)))
        lengths (mapv (fn [[a b]]
                        (let [[ax ay] (positions a) [bx by] (positions b)]
                          (Math/hypot (- ax bx) (- ay by)))) (:edges state))
        tilts (mapv (fn [[a b]]
                      (let [[ax ay] (positions a) [bx by] (positions b)
                            dx (Math/abs (- ax bx)) dy (Math/abs (- ay by))]
                        (Math/toDegrees (Math/atan2 (min dx dy) (max dx dy))))) (:edges state))
        count-edges (count lengths)
        mean (if (pos? count-edges) (/ (reduce + lengths) count-edges) 0.0)
        variance (if (pos? count-edges)
                   (/ (reduce + (map (fn [length] (let [d (- length mean)] (* d d))) lengths)) count-edges)
                   0.0)
        config (preferences/prepare (:normalized state) (:edges state)
                                    (assoc (:opts state) :stress-method :balanced))
        geometry (get-in state [:numeric :geometry])
        stress (preferences/base-energy (get-in state [:topology :packed]) geometry)
        penalty (preferences/energy config geometry)]
    (merge (select-keys (conflicts/analyze config geometry)
                        [:edge-overlaps :edge-crossings :node-edge-conflicts :node-overlaps
                         :edge-node-overlaps])
           {:mirror-pairs (count (:mirrors config))
            :mirror-error (Math/sqrt (symmetry/squared-error (:mirrors config) geometry))
            :edge-length-spread-percent (if (pos? mean) (* 100.0 (/ (Math/sqrt variance) mean)) 0.0)
     :mean-edge-length mean
     :minimum-edge-length (if (seq lengths) (reduce min lengths) 0.0)
     :mean-tilt-degrees (if (pos? count-edges) (/ (reduce + tilts) count-edges) 0.0)
     :graph-stress stress :preference-penalty penalty :combined-objective (+ stress penalty)})))

(defn compare-layouts
  ([graph] (compare-layouts graph {}))
  ([graph opts]
   (let [opts (merge {:iterations 250 :edge-length 100 :gap 12} opts)
         standard (nodes/advance-layout
                   (nodes/create-layout (:nodes graph) (:edges graph) (assoc opts :stress-method :standard)))
         symmetric (nodes/advance-layout
                    (nodes/create-balanced-layout (:nodes graph) (:edges graph) opts))]
     {:title (:title graph) :input (:nodes graph) :edges (:edges graph)
      :options (select-keys (:opts symmetric) [:iterations :edge-length :gap
                                              :edge-symmetry-weight :edge-axis-weight
                                              :edge-overlap-weight :edge-clearance :mirror-symmetry-weight])
      :standard {:nodes (:nodes standard) :metrics (summary standard)}
      :symmetric {:nodes (:nodes symmetric) :metrics (summary symmetric)}})))

(defn -main [& [output-path example]]
  (let [graph (case example
                nil (sample-graph)
                "overlap" (overlap-graph)
                (throw (ex-info "Unknown comparison example" {:example example})))
        result (compare-layouts graph)]
    (if (some? output-path)
      (spit output-path (json/write-json result))
      (println (json/write-json result)))
    (shutdown-agents)))
