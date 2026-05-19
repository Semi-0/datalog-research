(ns propagators.graph
  "Immutable directed graph: `id → [:node ...]`."
  )

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn node? [x] (tagged? x :node))
(defn node [id inputs outputs] [:node id (set inputs) (set outputs)])
(defn blank-node [id] (node id #{} #{}))
(def empty-graph {})
(defn graph? [x] (map? x))

(defn node-id [n] (nth n 1))
(defn node-input-ids [n] (nth n 2))
(defn node-output-ids [n] (nth n 3))

(defn get-node [graph id]
  (or (get graph id)
      (throw (ex-info "unknown node" {:id id :known (keys graph)}))))

(defn assoc-graph [graph id node] (assoc graph id node))

(defn node-inputs [graph node]
  (into #{} (map #(get-node graph %) (node-input-ids node))))

(defn node-outputs [graph node]
  (into #{} (map #(get-node graph %) (node-output-ids node))))

(defn link-edge [graph from-id to-id]
  (let [from (get-node graph from-id)
        to (get-node graph to-id)]
    (-> graph
        (assoc-graph from-id
                     (node (node-id from)
                           (node-input-ids from)
                           (conj (node-output-ids from) to-id)))
        (assoc-graph to-id
                     (node (node-id to)
                           (conj (node-input-ids to) from-id)
                           (node-output-ids to))))))
