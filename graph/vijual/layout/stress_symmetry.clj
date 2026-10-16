(ns graph.vijual.layout.stress-symmetry
  "Conservative twin-node mirror constraints and coordinated candidate moves."
  (:require [clojure.set :as set]
            [graph.vijual.layout.stress-conflicts :as conflicts]))

(defn prepare
  "Infer only two-member equal-size open/closed-neighborhood classes with at
  least two shared anchors. This is not a general graph-automorphism search."
  [normalized edges]
  (let [indices (zipmap (map :id normalized) (range))
        neighbors (reduce (fn [adj [a b]]
                            (let [a (indices a) b (indices b)]
                              (-> adj (update a conj b) (update b conj a))))
                          (vec (repeat (count normalized) #{})) edges)
        classes (fn [closed?]
                  (vals (group-by (fn [i]
                                    [(:size (normalized i))
                                     (if closed? (conj (neighbors i) i) (neighbors i))])
                                  (range (count normalized)))))
        pairs (distinct (map #(vec (sort %))
                             (filter #(= 2 (count %)) (concat (classes false) (classes true)))))]
    (vec (keep (fn [[a b]]
                 (let [anchors (vec (sort (set/intersection (neighbors a) (neighbors b))))]
                   (if (>= (count anchors) 2)
                     {:a a :b b :anchors anchors}
                     nil)))
               (sort pairs)))))

(defn- frame [geometry anchors]
  (let [points (mapv #(conflicts/point geometry %) anchors)
        n (count points)
        cx (/ (reduce + (map first points)) n) cy (/ (reduce + (map second points)) n)
        [xx xy yy] (reduce (fn [[xx xy yy] [x y]]
                             (let [dx (- x cx) dy (- y cy)]
                               [(+ xx (* dx dx)) (+ xy (* dx dy)) (+ yy (* dy dy))]))
                           [0.0 0.0 0.0] points)
        angle (* 0.5 (Math/atan2 (* 2.0 xy) (- xx yy)))]
    {:center [cx cy] :axis [(Math/cos angle) (Math/sin angle)]}))

(defn- reflect [[x y] {:keys [center axis]}]
  (let [[cx cy] center [ux uy] axis
        along (+ (* (- x cx) ux) (* (- y cy) uy))]
    [(- (+ cx (* 2.0 along ux)) (- x cx))
     (- (+ cy (* 2.0 along uy)) (- y cy))]))

(defn squared-error [pairs geometry]
  (reduce (fn [total {:keys [a b anchors]}]
            (let [[x y] (reflect (conflicts/point geometry a) (frame geometry anchors))
                  [bx by] (conflicts/point geometry b)]
              (+ total (* (- x bx) (- x bx)) (* (- y by) (- y by)))))
          0.0 pairs))

(defn energy [config geometry]
  (* (:mirror-weight config 0.0) (squared-error (:mirrors config) geometry)))

(defn- move-to [geometry i [x y]]
  (let [[px py] (conflicts/point geometry i)] [i [(- x px) (- y py)]]))

(defn- pair-proposals [config geometry pinned? {:keys [a b anchors]}]
  (let [{:keys [center axis] :as frame} (frame geometry anchors)
        [ax ay] (conflicts/point geometry a) [bx by] (conflicts/point geometry b)
        [rx ry] (reflect [bx by] frame)
        projected [(* 0.5 (+ ax rx)) (* 0.5 (+ ay ry))]
        [cx cy] center [ux uy] axis
        free-a? (not (aget ^booleans pinned? a)) free-b? (not (aget ^booleans pinned? b))]
    (cond
      (and free-a? free-b?)
      (cons [(move-to geometry a projected) (move-to geometry b (reflect projected frame))]
            (for [scale [0.5 0.75 1.0 1.25] sign [1.0 -1.0]
                  :let [radius (* sign scale (:target config))
                        point [(+ cx (* (- uy) radius)) (+ cy (* ux radius))]]]
              [(move-to geometry a point) (move-to geometry b (reflect point frame))]))
      free-a? [[(move-to geometry a (reflect [bx by] frame))]]
      free-b? [[(move-to geometry b (reflect [ax ay] frame))]]
      :else [])))

(defn proposals [config geometry pinned?]
  (if (pos? (:mirror-weight config 0.0))
    (mapcat #(pair-proposals config geometry pinned? %) (:mirrors config))
    []))
