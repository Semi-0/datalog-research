(ns differential-dataflow.frontier)

(defn version-lte?
  "Partial-order comparison for scalar or tuple/vector versions."
  [a b]
  (cond
    (and (number? a) (number? b))
    (clojure.core/<= a b)

    (and (vector? a) (vector? b) (= (count a) (count b)))
    (every? true? (map clojure.core/<= a b))

    :else
    (throw (ex-info "versions must both be numbers or same-length vectors"
                    {:a a :b b}))))

(defn version-lt?
  [a b]
  (and (version-lte? a b) (not (version-lte? b a))))

(defn version-eq?
  [a b]
  (and (version-lte? a b) (version-lte? b a)))

(defn version-lub
  "Least upper bound for scalar or tuple/vector versions."
  [a b]
  (cond
    (and (number? a) (number? b))
    (max a b)

    (and (vector? a) (vector? b) (= (count a) (count b)))
    (mapv max a b)

    :else
    (throw (ex-info "versions must both be numbers or same-length vectors"
                    {:a a :b b}))))

(defn version-extend
  "Enter a nested scope by appending an iteration coordinate."
  [v]
  (if (vector? v)
    (conj v 0)
    [v 0]))

(defn version-truncate
  "Leave a nested scope by removing the final coordinate."
  [v]
  (cond
    (not (vector? v)) v
    (= 2 (count v)) (first v)
    :else (pop v)))

(defn version-apply-step
  "Advance the final coordinate by `step`."
  [v step]
  (if (vector? v)
    (update v (dec (count v)) + step)
    (+ v step)))

(defn frontier
  "Normalize `versions` into a minimal antichain."
  [versions]
  (let [vs (vec (set versions))]
    (set
     (for [v vs
           :when (not-any? #(version-lt? % v) vs)]
       v))))

(defn frontier-lte-version?
  "True iff some frontier element is less than or equal to version `v`."
  [F v]
  (boolean (some #(version-lte? % v) F)))

(defn frontier-lte?
  "Antichain order used by differential/timely frontiers: F <= G when every
  element in G is covered by some element in F."
  [F G]
  (every? #(frontier-lte-version? F %) G))

(defn frontier-lt?
  [F G]
  (and (frontier-lte? F G) (not (frontier-lte? G F))))

(defn frontier-meet
  "Meet/frontier intersection: pairwise version LUB followed by antichain
  minimization. For scalar frontiers this is max."
  [F G]
  (frontier
   (for [f F
         g G]
     (version-lub f g))))

(defn frontier-map
  [f F]
  (frontier (map f F)))

(defn frontier-extend
  [F]
  (frontier-map version-extend F))

(defn frontier-truncate
  [F]
  (frontier-map version-truncate F))

(defn frontier-apply-step
  [F step]
  (frontier-map #(version-apply-step % step) F))

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

(def version:<= version-lte?)
(def version:<  version-lt?)
(def version:>= (fn [a b] (version-lte? b a)))
(def version:>  (fn [a b] (version-lt? b a)))
(def version:=  version-eq?)
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
  (frontier versions))

(defn frontier-advanced-by?
  [current announcement]
  (or (empty? current)
      (every? (fn [n]
                (some #(version:<= % n) current))
              announcement)))

(defn- merge-frontier-set
  "New antichain announcement: remove old corners strictly below a new one, then union.
  Assumes `current` is normalized."
  [current announcement]
  (if (frontier-advanced-by? current announcement)
    (let [closed? (fn [f] (some #(version:< f %) announcement))
          stripped (set (remove closed? current))]
      (normalize-frontier (into stripped announcement)))
    current))

(defn- merge-single-version
  "Advance with corner `v`. Assumes `current` is normalized."
  [current v]
  (cond
    (some #(version:< % v) current)
    (normalize-frontier
     (into (set (remove #(version:< % v) current)) #{v}))

    (or (some #(version:< v %) current)
        (some #(version:<= % v) current))
    current

    :else
    (normalize-frontier (conj current v))))

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

(defn- frontier-merge*
  "Combine `current` with `announcement`. Assumes `current` is already normalized."
  [current announcement]
  (let [F (set current)]
    (cond
      (epoch-close? announcement)
      (close-epoch-frontier F announcement)

      (version? announcement)
      (merge-single-version F announcement)

      (frontier? announcement)
      (merge-frontier-set F announcement)

      :else
      F)))

(defn frontier-merge
  "Combine current frontier with `announcement` (normalizes `current` first)."
  [current announcement]
  (frontier-merge* (normalize-frontier current) announcement))

(defn version-lowest-upper-bound [[a b] [c d]]
  (version-lub [a b] [c d]))

(defn normalize-frontier [versions]
  (frontier versions))

(defn frontier-upper-union [F G]
  (normalize-frontier (concat F G)))

(defn frontier-upper-intersection [F G]
  (frontier-meet F G))
