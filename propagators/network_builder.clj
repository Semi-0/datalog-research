(ns propagators.network-builder
  "Small construction helpers for immutable propagator networks."
  (:require [propagators.cells.cell :as cell]
            [propagators.core :as core]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn install-cell
  "Install a cell into `n`, returning the updated network."
  ([n id]
   (net/install-net n (cell/construct-cell id)))
  ([n id content strongest]
   (net/install-net n (cell/construct-cell id content strongest))))

(defn ensure-cell
  "Ensure `id` has both a cell entry and a graph node."
  [n id]
  (let [entry (get (net/net-env n) id)
        node (get (net/net-graph n) id)]
    (cond
      (and (cell/cell? entry) node)
      n

      (and (cell/cell? entry) (nil? node))
      (net/assoc-net-node n id (graph/blank-node))

      (and (nil? entry) (nil? node))
      (install-cell n id)

      (and (nil? entry) node)
      (throw (ex-info "cell graph node exists without an environment entry"
                      {:cell-id id}))

      :else
      (throw (ex-info "node ID is already occupied by a non-cell entry"
                      {:cell-id id
                       :entry entry})))))

(defn copy-cell
  "Install `id` in `n` with the strongest value from `source-net`."
  [n source-net id]
  (let [strongest (cell/cell-strongest (net/network-env-lookup source-net id))]
    (install-cell n id strongest strongest)))

(defn install-cells
  "Install empty cells for every id into a fresh network."
  [ids]
  (reduce install-cell net/empty-net ids))

(defn seed-cell
  "Set `id` to a cell whose content and strongest value are both `v`."
  [n id v]
  (net/assoc-net-cell n id (cell/cell v v)))

(defn install-propagator
  "Install a propagator with `installer`, returning `[prop-id updated-network]`."
  [n installer]
  (installer n))

(defn run-propagators
  "Run propagators by id on `n`."
  [n prop-ids]
  (core/run-tasks (tq/enqueue-all tq/empty-queue prop-ids) n))

(defn install-propagator!
  "Install a propagator and enqueue it, returning `[updated-network updated-tasks]`."
  [n tasks installer]
  (let [[prop-id n'] (install-propagator n installer)]
    [n' (tq/enqueue tasks prop-id)]))

(defn neighbor-propagator-ids
  "Propagator ids adjacent to cell `id` in `n`."
  [n id]
  (when-let [node (get (net/net-graph n) id)]
    (filter #(prop/prop? (get (net/net-env n) %))
            (into (vec (:inputs node)) (:outputs node)))))

(defn seed-cell!
  "Seed a cell and enqueue neighboring propagators, returning `[updated-network updated-tasks]`."
  [n tasks id v]
  (let [n' (seed-cell n id v)]
    [n' (tq/enqueue-all tasks (neighbor-propagator-ids n' id))]))

(defn add-named-cell
  "Install a named cell under dict key `k` with content and strongest `v`."
  [n k v]
  (let [id (ids/new-node-id)
        n' (install-cell n id v v)]
    (net/assoc-net-dict-entry n' k id)))

(defn named-cell-net
  "Build a fresh network from `[dict-key value]` pairs."
  [named-values]
  (reduce
   (fn [n [k v]]
     (add-named-cell n k v))
   net/empty-net
   named-values))
