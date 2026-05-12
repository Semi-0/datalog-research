(ns differential-dataflow.multiset
  "Keyed multiset rows `[[[k v] m] …]`. Call via ns alias.
  `map` / `filter` are curried in `f`: `(ms/map f rows)` or `((ms/map f) rows)`."
  (:refer-clojure :exclude [map filter])
  (:require [clojure.core :as c]))

(defn group-by-key [rows]
  (c/reduce (fn [m [[k v] mult]] (update m k (fnil conj []) [v mult])) {} rows))

(defn map
  ([f]
   (fn [pairs]
     (mapv (fn [[d m]] [(f d) m]) pairs)))
  ([f pairs]
   ((map f) pairs)))

(defn filter
  ([pred]
   (fn [pairs]
     (filterv (fn [[d _]] (boolean (pred d))) pairs)))
  ([pred pairs]
   ((filter pred) pairs)))

(defn negate [pairs]
  (mapv (fn [[d m]] [d (- m)]) pairs))

(def append c/into)

(defn extend-rows [set other]
  (append set other))

(defn consolidate [pairs]
  (vec (c/sort (c/filter (fn [[_ mult]] (not= mult 0))
                         (c/reduce (fn [acc [d mult]]
                                     (assoc acc d (+ (long (get acc d 0)) (long mult))))
                                   {}
                                   pairs)))))

(defn reduce-by-key [rows reducer init]
  (let [g (group-by-key rows)]
    (c/into {} (c/map (fn [k] [k (c/reduce reducer init (g k))]) (c/sort (c/keys g))))))

(defn fold
  ([rows reducer init]
   (fold rows reducer init (fn [acc] [[acc 1]])))
  ([rows reducer init emit]
   (let [g (group-by-key rows)]
     (consolidate
       (vec (c/mapcat (fn [k] (c/map (fn [[v m]] [[k v] m])
                                    (emit (c/reduce reducer init (g k)))))
                     (c/sort (c/keys g))))))))

(defn tally [rows]
  (fold rows (fn [a [_ mult]] (+ a (long mult))) 0 (fn [c] [[c 1]])))

(defn sum [rows]
  (fold rows (fn [a [v mult]] (+ a (* (long v) (long mult)))) 0 (fn [s] [[s 1]])))

(defn- pick-inner [better label vals]
  (let [xs (consolidate vals)]
    (if (empty? xs) []
      (let [[[v0 m0] & more] xs]
        (if-not (pos? m0)
          (throw (ex-info (str label " needs positive multiplicity") {:v v0 :m m0}))
          [[(c/reduce (fn [best [v mult]]
                        (if-not (pos? mult)
                          (throw (ex-info (str label " needs positive multiplicity") {:v v :m mult}))
                          (better v best)))
                      v0
                      more)
            1]])))))

(defn minimum [rows]
  (fold rows conj [] #(pick-inner (fn [v b] (if (< v b) v b)) "min" %)))

(defn maximum [rows]
  (fold rows conj [] #(pick-inner (fn [v b] (if (> v b) v b)) "max" %)))

(defn- distinct-inner [vals]
  (let [xs (consolidate vals)]
    (if (empty? xs) []
      (do (doseq [[_ mult] xs] (when-not (pos? mult) (throw (ex-info "distinct needs positive multiplicity" {:m mult}))))
          (mapv (fn [[v _]] [v 1]) xs)))))

(defn unique [rows]
  (fold rows conj [] distinct-inner))

(defn join [rows-a rows-b]
  (vec (for [[[k1 v1] d1] rows-a [[k2 v2] d2] rows-b :when (= k1 k2)]
         [[k1 [v1 v2]] (* (long d1) (long d2))])))

(defn converge [f coll]
  (loop [curr (consolidate coll)]
    (let [nxt (consolidate (f curr))]
      (if (= nxt curr) curr (recur nxt)))))

(defn difference [a b]
  (consolidate (append a (negate b))))
