(ns graph.vijual.layout.stress-preferences
  "Optional length, axis, twin-node mirror and geometric-clearance stress terms.
  Ordinary stress, node sizes and pins remain active; twin nodes can mirror."
  (:require [graph.vijual.layout.stress-conflicts :as conflicts]
            [graph.vijual.layout.stress-symmetry :as symmetry]))

(defn prepare [normalized edges opts]
  (if (and (contains? #{:balanced :symmetric} (:stress-method opts))
           (or (pos? (:edge-symmetry-weight opts)) (pos? (:edge-axis-weight opts))
               (pos? (:edge-overlap-weight opts)) (pos? (:mirror-symmetry-weight opts)))
           (seq edges))
    (let [indices (zipmap (map :id normalized) (range))
          radii (into {} (map (fn [{:keys [id size]}]
                               [id (* 0.5 (apply #(Math/hypot %1 %2) size))]) normalized))
          ;; A common target respects the largest clearance of any actual edge.
          target (reduce (fn [distance [a b]]
                           (max distance (+ (radii a) (radii b) (:gap opts))))
                         (:edge-length opts) edges)]
      {:aa (long-array (map (comp indices first) edges))
       :bb (long-array (map (comp indices second) edges))
       :target (double target)
       :symmetry-weight (double (:edge-symmetry-weight opts))
       :mirror-weight (double (:mirror-symmetry-weight opts))
       :mirrors (if (pos? (:mirror-symmetry-weight opts))
                  (symmetry/prepare normalized edges)
                  [])
       :axis-weight (double (:edge-axis-weight opts))
       :overlap-weight (double (:edge-overlap-weight opts))
       :edge-clearance (double (:edge-clearance opts))
       :node-gap (double (:gap opts))
       :half-widths (double-array (map (comp #(* 0.5 %) first :size) normalized))
       :half-heights (double-array (map (comp #(* 0.5 %) second :size) normalized))})
    nil))

(defn buffers [n]
  {:sx (double-array n) :sy (double-array n)
   :wx (double-array n) :wy (double-array n)})

(defn accumulate!
  "Majorize common-length springs and min(dx^2,dy^2) axis penalties at current
  geometry. The nearest axis is selected each iteration; ties select horizontal."
  [config geometry sums]
  (doseq [key [:sx :sy :wx :wy]] (java.util.Arrays/fill ^doubles (get sums key) 0.0))
  (let [^longs aa (:aa config) ^longs bb (:bb config)
        ^doubles xs (:xs geometry) ^doubles ys (:ys geometry)
        ^doubles sx (:sx sums) ^doubles sy (:sy sums)
        ^doubles wx (:wx sums) ^doubles wy (:wy sums)
        distance (double (:target config)) weight (double (:symmetry-weight config))
        axis-weight (double (:axis-weight config))]
    (dotimes [i (alength aa)]
      (let [a (int (aget aa i)) b (int (aget bb i))
            ax (aget xs a) ay (aget ys a) bx (aget xs b) by (aget ys b)
            dx (- ax bx) dy (- ay by)
            length (Math/max 1.0e-6 (Math/hypot dx dy))
            ux (/ dx length) uy (/ dy length)]
        (aset-double sx a (+ (aget sx a) (* weight (+ bx (* distance ux)))))
        (aset-double sy a (+ (aget sy a) (* weight (+ by (* distance uy)))))
        (aset-double sx b (+ (aget sx b) (* weight (- ax (* distance ux)))))
        (aset-double sy b (+ (aget sy b) (* weight (- ay (* distance uy)))))
        (doseq [node [a b]]
          (aset-double wx node (+ (aget wx node) weight))
          (aset-double wy node (+ (aget wy node) weight)))
        (if (>= (Math/abs dx) (Math/abs dy))
          (do (aset-double sy a (+ (aget sy a) (* axis-weight by)))
              (aset-double sy b (+ (aget sy b) (* axis-weight ay)))
              (aset-double wy a (+ (aget wy a) axis-weight))
              (aset-double wy b (+ (aget wy b) axis-weight)))
          (do (aset-double sx a (+ (aget sx a) (* axis-weight bx)))
              (aset-double sx b (+ (aget sx b) (* axis-weight ax)))
              (aset-double wx a (+ (aget wx a) axis-weight))
              (aset-double wx b (+ (aget wx b) axis-weight))))))))

(defn shape-energy [config geometry]
  (if (some? config)
    (let [^longs aa (:aa config) ^longs bb (:bb config)
          ^doubles xs (:xs geometry) ^doubles ys (:ys geometry)
          target (double (:target config))
          weight (double (:symmetry-weight config)) axis-weight (double (:axis-weight config))]
      (loop [i 0 total 0.0]
        (if (= i (alength aa))
          total
          (let [a (aget aa i) b (aget bb i)
                dx (- (aget xs a) (aget xs b)) dy (- (aget ys a) (aget ys b))
                error (- (Math/hypot dx dy) target)]
            (recur (inc i) (+ total (* weight error error)
                             (* axis-weight (Math/min (* dx dx) (* dy dy)))))))))
    0.0))

(defn energy [config geometry]
  (+ (shape-energy config geometry) (symmetry/energy config geometry)
     (:penalty (conflicts/analyze config geometry))))

(defn base-energy
  "Evaluate the original size-aware all-pairs objective, without preferences."
  [packed geometry]
  (let [^longs aa (:aa packed) ^longs bb (:bb packed)
        ^doubles ds (:ds packed) ^doubles ws (:ws packed)
        ^doubles xs (:xs geometry) ^doubles ys (:ys geometry)
        ^doubles radii (:radii geometry) gap (double (:gap packed))]
    (loop [i 0 total 0.0]
      (if (= i (alength aa))
        total
        (let [a (aget aa i) b (aget bb i)
              actual (Math/hypot (- (aget xs a) (aget xs b)) (- (aget ys a) (aget ys b)))
              target (Math/max (aget ds i) (+ (aget radii a) (aget radii b) gap))
              delta (- actual target)]
          (recur (inc i) (+ total (* (aget ws i) delta delta))))))))
