(ns graph.vijual.layout.stress-numeric
  "Private numeric buffers. Every advance owns its writes; prior states are read-only.
  Cached sums always carry the geometry at which they were evaluated.")

(defn geometry [normalized]
  {:xs (double-array (map (comp first :position) normalized))
   :ys (double-array (map (comp second :position) normalized))
   :radii (double-array (map (fn [{[w h] :size}] (* 0.5 (Math/hypot w h))) normalized))})

(defn positions [{:keys [xs ys]}]
  (mapv (fn [i] [(aget ^doubles xs i) (aget ^doubles ys i)])
        (range (alength ^doubles xs))))

(defn- changed-indices [before after]
  (let [^doubles ax (:xs before) ^doubles ay (:ys before) ^doubles ar (:radii before)
        ^doubles bx (:xs after) ^doubles by (:ys after) ^doubles br (:radii after)]
    (filterv (fn [i] (or (not= (aget ax i) (aget bx i))
                         (not= (aget ay i) (aget by i))
                         (not= (aget ar i) (aget br i))))
             (range (alength bx)))))

(defn same-geometry? [before after]
  (empty? (changed-indices before after)))

(defn initial [normalized]
  {:geometry (geometry normalized) :basis nil :sums nil})

(defn edit [numeric normalized topology-changed?]
  (let [next-geometry (geometry normalized)]
    (if topology-changed?
      {:geometry next-geometry :basis nil :sums nil}
      (if (same-geometry? (:geometry numeric) next-geometry)
        numeric
        (assoc numeric :geometry next-geometry)))))

(defn- accumulate! [packed geometry sums indices sign]
  (let [^longs aa (:aa packed) ^longs bb (:bb packed)
        ^doubles ds (:ds packed) ^doubles ws (:ws packed)
        ^doubles xs (:xs geometry) ^doubles ys (:ys geometry)
        ^doubles radii (:radii geometry)
        ^doubles sx (:sx sums) ^doubles sy (:sy sums)
        gap (double (:gap packed)) factor (double sign)
        limit (if (some? indices) (alength ^longs indices) (alength aa))]
    (dotimes [i limit]
      (let [p (long (if (some? indices) (aget ^longs indices i) i))
            a (int (aget aa p)) b (int (aget bb p))
            weight (* factor (aget ws p))]
        (when-not (zero? weight)
          (let [ax (aget xs a) ay (aget ys a) bx (aget xs b) by (aget ys b)
                dx (- ax bx) dy (- ay by)
                length (Math/max 1.0e-6 (Math/sqrt (+ (* dx dx) (* dy dy))))
                d (Math/max (aget ds p) (+ (aget radii a) (aget radii b) gap))
                ux (/ dx length) uy (/ dy length)]
            (aset-double sx a (+ (aget sx a) (* weight (+ bx (* d ux)))))
            (aset-double sy a (+ (aget sy a) (* weight (+ by (* d uy)))))
            (aset-double sx b (+ (aget sx b) (* weight (- ax (* d ux)))))
            (aset-double sy b (+ (aget sy b) (* weight (- ay (* d uy)))))))))))

(defn- incident-pairs [n dirty]
  (let [k (count dirty)
        length (+ (* k (- n k)) (quot (* k (dec k)) 2))
        result (long-array length)
        dirty? (boolean-array n)
        cursor (int-array 1)]
    (doseq [a dirty] (aset-boolean dirty? a true))
    (doseq [i dirty]
      (dotimes [j n]
        (when (and (not= i j) (or (not (aget dirty? j)) (< i j)))
          (let [a (min i j) b (max i j)
                p (+ (quot (* a (- (* 2 n) a 1)) 2) (- b a 1))
                at (aget cursor 0)]
            (aset-long result at p)
            (aset-int cursor 0 (inc at))))))
    result))

(defn- refresh-sums! [packed geometry basis sums]
  (let [dirty (if (some? basis) (changed-indices basis geometry) [])
        k (count dirty) n (:n packed)
        touched (+ (* k (- n k)) (quot (* k (dec k)) 2))]
    (cond
      (and (some? basis) (zero? k))
      {:pair-evaluations 0 :sparse-sweeps 0 :dense-sweeps 0}

      (and (some? basis) (< (* 2 touched) (:pair-count packed)))
      (let [indices (incident-pairs n dirty)]
        (accumulate! packed basis sums indices -1.0)
        (accumulate! packed geometry sums indices 1.0)
        {:pair-evaluations (* 2 touched) :sparse-sweeps 1 :dense-sweeps 0})

      :else
      (do (java.util.Arrays/fill ^doubles (:sx sums) 0.0)
          (java.util.Arrays/fill ^doubles (:sy sums) 0.0)
          (accumulate! packed geometry sums nil 1.0)
          {:pair-evaluations (:pair-count packed) :sparse-sweeps 0 :dense-sweeps 1}))))

(defn- copy-geometry [geometry]
  (assoc geometry :xs (aclone ^doubles (:xs geometry)) :ys (aclone ^doubles (:ys geometry))))

(defn- separate-coincident! [packed geometry pinned?]
  (let [^doubles xs (:xs geometry) ^doubles ys (:ys geometry)
        ^doubles totals (:totals packed) n (:n packed)
        counts (frequencies (for [i (range n) :when (pos? (aget totals i))]
                              [(aget xs i) (aget ys i)]))]
    (dotimes [i n]
      (when (and (not (aget ^booleans pinned? i)) (pos? (aget totals i))
                 (> (get counts [(aget xs i) (aget ys i)] 0) 1))
        (let [angle (* 2.0 Math/PI (/ i (max 1 n)))]
          (aset-double xs i (+ (aget xs i) (* 1.0e-3 (Math/cos angle))))
          (aset-double ys i (+ (aget ys i) (* 1.0e-3 (Math/sin angle)))))))))

(defn- move! [packed geometry next-geometry sums pinned?]
  (let [^doubles xs (:xs geometry) ^doubles ys (:ys geometry)
        ^doubles nx (:xs next-geometry) ^doubles ny (:ys next-geometry)
        ^doubles sx (:sx sums) ^doubles sy (:sy sums)
        ^doubles totals (:totals packed)]
    (loop [i 0 maximum 0.0]
      (if (= i (alength xs))
        maximum
        (let [weight (aget totals i)
              free? (and (not (aget ^booleans pinned? i)) (pos? weight))
              x (if free? (* 0.5 (+ (aget xs i) (/ (aget sx i) weight))) (aget xs i))
              y (if free? (* 0.5 (+ (aget ys i) (/ (aget sy i) weight))) (aget ys i))]
          (when-not (and (Double/isFinite x) (Double/isFinite y))
            (throw (ex-info "Stress calculation exceeded finite coordinates" {:index i})))
          (aset-double nx i x)
          (aset-double ny i y)
          (recur (inc i) (Math/max maximum (Math/hypot (- x (aget xs i))
                                                       (- y (aget ys i))))))))))

(def empty-metrics
  {:iterations 0 :pair-evaluations 0 :dense-sweeps 0 :sparse-sweeps 0
   :max-displacement 0.0})

(defn advance [numeric packed pinned-indices {:keys [iterations tolerance]}]
  (let [n (:n packed) pinned? (boolean-array n)
        _ (doseq [i pinned-indices] (aset-boolean pinned? i true))
        movable? (some (fn [i] (and (not (aget pinned? i))
                                    (pos? (aget ^doubles (:totals packed) i)))) (range n))]
    (if (or (zero? iterations) (not movable?))
      [numeric empty-metrics]
      (let [geometry (copy-geometry (:geometry numeric))
            spare (assoc geometry :xs (double-array n) :ys (double-array n))
            sums (if (some? (:sums numeric))
                   {:sx (aclone ^doubles (get-in numeric [:sums :sx]))
                    :sy (aclone ^doubles (get-in numeric [:sums :sy]))}
                   {:sx (double-array n) :sy (double-array n)})]
        (separate-coincident! packed geometry pinned?)
        (loop [geometry geometry spare spare basis (:basis numeric) metrics empty-metrics]
          (let [work (refresh-sums! packed geometry basis sums)
                displacement (move! packed geometry spare sums pinned?)
                metrics (assoc (merge-with + metrics work)
                               :iterations (inc (:iterations metrics))
                               :max-displacement displacement)]
            (if (or (>= (:iterations metrics) iterations) (<= displacement tolerance))
              [{:geometry spare :basis geometry :sums sums} metrics]
              (recur spare geometry geometry metrics))))))))
