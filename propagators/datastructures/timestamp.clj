(ns propagators.datastructures.timestamp
  "The existing event timestamp ordering, shared with support projections.")

(declare time-rank)

(defn- evidence-timestamp? [x]
  (and (map? x)
       (contains? x :input-id)
       (contains? x :source)
       (contains? x :timestamp)))

(defn- evidence-identity-rank [e]
  [(pr-str (:input-id e)) (pr-str (:source e))])

(defn- evidence-entry-rank [e]
  (conj (evidence-identity-rank e) (time-rank (:timestamp e))))

(defn- newer-evidence-entry [a b]
  (if (pos? (compare (time-rank (:timestamp b))
                     (time-rank (:timestamp a))))
    b
    a))

(defn canonical-evidence-set [xs]
  (set
   (vals
    (reduce (fn [latest e]
              (update latest [(:input-id e) (:source e)]
                      (fn [current]
                        (if current (newer-evidence-entry current e) e))))
            {} xs))))

(defn- evidence-set-rank [xs]
  ["evidence-set"
   (mapv evidence-entry-rank
         (sort-by evidence-identity-rank (canonical-evidence-set xs)))])

(defn- map-time-rank [m]
  (if (evidence-timestamp? m)
    ["evidence" (evidence-entry-rank m)]
    ["map"
     (mapv (fn [[k v]] [(pr-str k) (time-rank v)])
           (sort-by (comp pr-str key) m))]))

(defn- collection-time-rank [tag xs]
  [tag (mapv time-rank (sort-by pr-str xs))])

(defn time-rank [t]
  (cond
    (number? t) ["scalar" t]
    (inst? t) ["scalar" (.getTime ^java.util.Date t)]
    (and (set? t) (every? evidence-timestamp? t)) (evidence-set-rank t)
    (map? t) (map-time-rank t)
    (set? t) (collection-time-rank "set" t)
    (sequential? t) (collection-time-rank "seq" t)
    :else ["value" (pr-str t)]))
