(ns differential-dataflow.multiset
  "Keyed multiset rows `[[[k v] m] ...]`. TS-shaped API: `multiset-group-by-key` → `multiset-reduce-by-key` / `multiset-reduce`.")

(defn multiset-group-by-key [rows]
  (reduce (fn [m [[k v] mult]] (update m k (fnil conj []) [v mult])) {} rows))

(defn multiset-map [f pairs]
  (mapv (fn [[d m]] [(f d) m]) pairs))

(defn multiset-filter [pred pairs]
  (filterv (fn [[d _]] (boolean (pred d))) pairs))

(defn multiset-negate [pairs]
  (mapv (fn [[d m]] [d (- m)]) pairs))

(def multiset-append into)
(defn multiset-extend [set other] (multiset-append set other))

(defn multiset-consolidate [pairs]
  (vec (sort (filter (fn [[_ mult]] (not= mult 0))
                     (reduce (fn [acc [d mult]]
                               (assoc acc d (+ (long (get acc d 0)) (long mult))))
                             {}
                             pairs)))))

(defn multiset-reduce-by-key [rows reducer init]
  (let [g (multiset-group-by-key rows)]
    (into {} (map (fn [k] [k (reduce reducer init (g k))]) (sort (keys g))))))

(defn multiset-reduce
  ([rows reducer init]
   (multiset-reduce rows reducer init (fn [acc] [[acc 1]])))
  ([rows reducer init emit]
   (let [g (multiset-group-by-key rows)]
     (->> (sort (keys g))
          (mapcat (fn [k]
                    (map (fn [[v m]] [[k v] m])
                         (emit (reduce reducer init (g k))))))
          vec
          multiset-consolidate))))

(defn multiset-count [rows]
  (multiset-reduce rows (fn [a [_ mult]] (+ a (long mult))) 0 (fn [c] [[c 1]])))

(defn multiset-sum [rows]
  (multiset-reduce rows (fn [a [v mult]] (+ a (* (long v) (long mult)))) 0 (fn [s] [[s 1]])))

(defn- multiset-pick-inner [better label vals]
  (let [xs (multiset-consolidate vals)]
    (if (empty? xs) []
      (let [[[v0 m0] & more] xs]
        (if-not (pos? m0)
          (throw (ex-info (str label " needs positive multiplicity") {:v v0 :m m0}))
          [[(reduce (fn [best [v mult]]
                       (if-not (pos? mult)
                         (throw (ex-info (str label " needs positive multiplicity") {:v v :m mult}))
                         (better v best)))
                     v0
                     more)
            1]])))))

(defn multiset-min [rows]
  (multiset-reduce rows conj [] #(multiset-pick-inner (fn [v b] (if (< v b) v b)) "min" %)))

(defn multiset-max [rows]
  (multiset-reduce rows conj [] #(multiset-pick-inner (fn [v b] (if (> v b) v b)) "max" %)))

(defn- multiset-distinct-inner [vals]
  (let [xs (multiset-consolidate vals)]
    (if (empty? xs) []
      (do (doseq [[_ mult] xs] (when-not (pos? mult) (throw (ex-info "distinct needs positive multiplicity" {:m mult}))))
          (mapv (fn [[v _]] [v 1]) xs)))))

(defn multiset-distinct [rows]
  (multiset-reduce rows conj [] multiset-distinct-inner))

(defn multiset-join [rows-a rows-b]
  (vec (for [[[k1 v1] d1] rows-a [[k2 v2] d2] rows-b :when (= k1 k2)]
         [[k1 [v1 v2]] (* (long d1) (long d2))])))

(defn multiset-iterate [f coll]
  (loop [curr (multiset-consolidate coll)]
    (let [nxt (multiset-consolidate (f curr))]
      (if (= nxt curr) curr (recur nxt)))))

(defn multiset-difference [a b]
  (multiset-consolidate (multiset-append a (multiset-negate b))))
