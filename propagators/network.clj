(ns propagators.network
  (:require [as-messages :refer [as-messages strongest-from-snapshot]]
            [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.ids :refer [new-node-id]]
            [propagators.propagator :as prop]))

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn net? [x] (tagged? x :net))
(defn net [graph env] [:net graph env])
(def empty-net (net {} {}))
(def empty-network empty-net)
(defn network? [x] (net? x))

(defn net-graph [n] (nth n 1))
(defn net-env [n] (nth n 2))
(defn net-with-graph [n graph] (net graph (net-env n)))
(defn net-with-env [n env] (net (net-graph n) env))

(defn as-net
  "Coerce `[:net g e]` or legacy `[g e]` to network."
  [x]
  (if (net? x) x (net (first x) (second x))))

(def empty-env {})
(defn env? [x] (map? x))
(defn env-get [env id] (get env id))
(defn assoc-env [env id entry] (assoc env id entry))

(defn assoc-net-node [n id node]
  (net-with-graph n (graph/assoc-graph (net-graph n) id node)))

(defn assoc-net-cell [n id c]
  (net-with-env n (assoc-env (net-env n) id c)))

(defn assoc-net-prop [n id p]
  (net-with-env n (assoc-env (net-env n) id p)))

(defn network-env-lookup
  "Env entry for cell or propagator at `node-id` token `[:node-id …]`."
  [network node-id]
  (env-get (net-env network) (graph/node-id node-id)))

(defn network-cell-strongest [network node-id]
  (cell/cell-strongest (network-env-lookup network node-id)))

(defn network-cell-content [network node-id]
  (cell/cell-content (network-env-lookup network node-id)))

(def network-lookup-cell network-env-lookup)

(def network-lookup-propagator network-env-lookup)

(defn- wire-propagator-edges [g prop-id inputs outputs]
  (let [ins (set inputs)
        outs (set outputs)]
    (reduce (fn [g' in-id] (graph/link-edge g' in-id prop-id))
            (reduce (fn [g' out-id] (graph/link-edge g' prop-id out-id))
                    g
                    outs)
            ins)))

(defn update-net-cell
  "Apply `message` (CellValue) to cell at `id`; returns updated net."
  [n id msg]
  (let [e (net-env n)
        cur (env-get e id)
        content' (merge/cell-merge (cell/cell-content cur) msg n)
        strongest' (merge/strongest-value content' n)]
    (assoc-net-cell n id (cell/cell content' strongest'))))

(defn construct-cell
  ([]
   (construct-cell (new-node-id)))
  ([id]
   (fn [arg]
     (let [net (as-net arg)
           n (-> net
                 (assoc-net-node id (graph/blank-node))
                 (assoc-net-cell id (cell/cell value/nothing value/nothing)))]
       [id n])))
  ([id content strongest]
   (fn [arg]
     (let [net (as-net arg)
           n (-> net
                 (assoc-net-node id (graph/blank-node))
                 (assoc-net-cell id (cell/cell content strongest)))]
       [id n]))))

(defn install-net
  "Run installer `f` (`f` takes a net, returns `[id net']`). Returns the new net."
  [n f]
  (second (f n)))

(defn seed-net-cell
  "Install or update cell `id` on `n`. With content/strongest, seeds that cell value."
  ([n id]
   (install-net n (construct-cell id)))
  ([n id content strongest]
   (install-net n (construct-cell id content strongest))))

(defn construct-propagator
  ([activate inputs outputs]
   (construct-propagator (new-node-id) activate inputs outputs))
  ([id activate inputs outputs]
   (fn [arg]
     (let [net (as-net arg)
           ins (set inputs)
           outs (set outputs)
           g (net-graph net)
           g' (-> g
                  (graph/assoc-graph id (graph/node ins outs))
                  (wire-propagator-edges id ins outs))
           n (-> net
                 (net-with-graph g')
                 (assoc-net-prop id (prop/prop activate)))]
       [id n]))))

(defn primitive-propagator
  "Installer for a primitive propagator. Call with node-id tokens (variadic):
  all but the last are inputs, the last is the output cell.

  Example: `((p:id c-in c-out) net)` or `(net/install-net net (p:id c-in c-out))`."
  [f]
  (fn [& node-ids]
    (let [nodes (vec node-ids)
          inputs (vec (butlast nodes))
          output (last nodes)
          wrapped-f (fn [input-nodes output-nodes network]
                      (let [input-cells (mapv (partial network-env-lookup network) input-nodes)
                            in-vals (mapv cell/cell-strongest input-cells)]
                        (if (value/any-unusable-values? in-vals)
                          []
                          (as-messages output-nodes [(apply f in-vals)]))))]
      (construct-propagator wrapped-f inputs [output]))))
