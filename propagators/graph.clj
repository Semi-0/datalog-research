(ns propagators.graph
  "Immutable directed graph: `adj` is `node → #{successor}`.

  Nodes may be keywords, numbers, `propagators.cell/Cell`, etc. Bulk build:
  `edges->graph`.")

(defrecord Graph [adj])

(defn graph? [x]
  (instance? Graph x))

(defn empty-graph []
  (->Graph {}))

(defn edges->graph
  "`edges` is a sequence of `[from to]`. Returns a persistent `Graph`."
  [edges]
  (->Graph
    (persistent!
      (reduce (fn [m [from to]]
                (assoc! m from (conj (or (get m from) #{}) to)))
              (transient {})
              edges))))

(defn add-edge [^Graph g from to]
  (->Graph (update (:adj g) from (fnil conj #{}) to)))

(defn neighbors
  "Outgoing neighbors of `node`."
  [^Graph g node]
  (get (:adj g) node #{}))

(def out neighbors)

(defn nodes [^Graph g]
  (let [a (:adj g)]
    (into (set (keys a)) (mapcat identity (vals a)))))
