(ns propagators.graph
  "Immutable directed graph: a map of nodes.

  A node is `id` + `inputs` (predecessor ids) + `outputs` (successor ids).

  Build with `node` + `add-node`, or `edges->graph` from `[from to]` pairs.")

(defrecord Node [id inputs outputs])

(defn node?
  [x]
  (instance? Node x))

(defn node
  "Create a node. `inputs` / `outputs` are sets (or seqs) of node ids."
  [id inputs outputs]
  (->Node id (set inputs) (set outputs)))

(defn- blank-node [id]
  (node id #{} #{}))

(defn- ensure-node [nodes id]
  (if (contains? nodes id)
    nodes
    (assoc nodes id (blank-node id))))

(defn graph?
  "True for a graph map `id → Node`."
  [x]
  (map? x))

(defn get-node
  "Look up a node by id in `graph`."
  [graph id]
  (or (get graph id)
      (throw (ex-info "unknown node" {:id id :known (keys graph)}))))

(defn node-inputs
  "Resolve `node`'s `:inputs` ids to predecessor `Node` records in `graph`."
  [graph ^Node node]
  (into #{} (map #(get-node graph %) (:inputs node))))

(defn node-outputs
  "Resolve `node`'s `:outputs` ids to successor `Node` records in `graph`."
  [graph ^Node node]
  (into #{} (map #(get-node graph %) (:outputs node))))

