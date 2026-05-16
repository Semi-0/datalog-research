(ns differential-dataflow.frontier)

(defn make-version
  [timestamp iteration]
  [timestamp iteration])

(defn version?
  [v]
  (and (vector? v)
       (= 2 (count v))
       (every? number? v)))

(def version-order
  #{:below :equal :above :incomparable})

(defn compare-versions [a b]
  (let [a<=b (every? true? (map <= a b))
        a>=b (every? true? (map >= a b))]
    (cond
      (and a<=b a>=b) :equal
      a<=b            :below
      a>=b            :above
      :else           :incomparable)))

(defn version-timestamp
  [v]
  (get v 0))

(defn version-iteration
  [v]
  (get v 1))

(defn version-comparator
  "Return (fn [a b] boolean) true when (compare-versions a b) is in `allowed`."
  [allowed]
  (fn [a b]
    (contains? allowed (compare-versions a b))))

(def version:<= (version-comparator #{:below :equal}))
(def version:<  (version-comparator #{:below}))
(def version:>= (version-comparator #{:above :equal}))
(def version:>  (version-comparator #{:above}))
(def version:=  (version-comparator #{:equal}))
(def version:incomparable? (version-comparator #{:incomparable}))

(defn- distinct-version-pairs
  "Unordered distinct pairs from a collection of versions."
  [coll]
  (let [vs (vec (set coll))]
    (for [i (range (count vs)) 
          j (range (inc i) (count vs))]
      [(nth vs i) (nth vs j)])))

(defn frontier?
  "True when x is an antichain of valid versions."
  [x]
  (and (coll? x)
       (every? version? x)
       (every? (fn [[u v]]
                 (version:incomparable? u v))
               (distinct-version-pairs x))))

(defn epoch-close?
  [x]
  (and (vector? x)
       (= 1 (count x))
       (number? (first x))))

(defn normalize-frontier
  "Drop any version strictly dominated by another in the collection."
  [versions]
  (let [vs (vec (set versions))]
    (set (for [v vs :when (not (some #(version:< % v) vs))]
           v))))

(defn frontier-advanced-by?
  [current announcement]
  (or (empty? current)
      (every? (fn [n]
                (some #(version:<= % n) current))
              announcement)))

(defn- merge-frontier-set
  "New antichain announcement: remove old corners strictly below a new one, then union."
  [existed announcement]
  (if (frontier-advanced-by? existed announcement)
    (let [closed? (fn [f] (some #(version:< f %) announcement))
          stripped (set (remove closed? existed))]
      (normalize-frontier (into stripped announcement)))
    existed))

(defn close-epoch-frontier
  "Treat [e] as an outer epoch closure marker.

  It removes all inner frontier corners whose epoch is strictly below e,
  and inserts [e 0] as the new outer/inner entry point."
  [existed [e]]
  (let [target [e 0]
        keep?  (fn [[epoch iteration]]
                 (>= epoch e))]
    (normalize-frontier
     (conj (set (filter keep? existed))
           target))))

(defn frontier-merge
  "Combine current frontier `existed` with announcement `new`.

  - `new` is a version vector: drop old corners below `new`, or append if incomparable.
  - `new` is an antichain set: drop any old corner strictly below a new one, union, normalize.
  - Otherwise: reject and return `existed`."
  [existed new]
  (let [F (set existed)]
    (cond
      (epoch-close? new)
      (close-epoch-frontier F new)

      (version? new)
      (merge-frontier-set F #{new})

      (frontier? new)
      (merge-frontier-set F new)

      :else
      F)))