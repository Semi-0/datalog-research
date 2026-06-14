(ns propagators.propagator
  (:require [as-messages :refer [as-messages]]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :refer [as-net
                                         assoc-net-prop
                                         net-graph
                                         net-with-graph
                                         network-env-lookup]]))

(defrecord Propagator [activate])

(defn prop?
  [x]
  (and (map? x) (contains? x :activate) (ifn? (:activate x))))

(defn prop [f] (map->Propagator {:activate f}))
(defn prop-f [p] (:activate p))
(def make-propagator prop)
(defn propagator? [x] (prop? x))

(defn input-values
  "Return strongest values for `input-ids` in `network`."
  [network input-ids]
  (mapv (fn [input-id]
          (let [entry (network-env-lookup network input-id)]
            (if (cell/cell? entry)
              (cell/cell-strongest entry)
              nil)))
        input-ids))

(defn concrete-inputs?
  "True when every input cell has a usable strongest value."
  [network input-ids]
  (not (apply value/any-unusable-values? (input-values network input-ids))))

(defn concrete-propagator
  "Wrap an activation so it runs only when all input cells are concrete.

  This is the common activation shape:

  (fn [inputs outputs network]
    (if (all-inputs-usable? inputs network)
      (f inputs outputs network)
      []))

  The wrapper does not inspect outputs and does not change the activation return
  protocol. It is only for propagators whose whole activation is invalid until
  every input is neither `nothing` nor `contradiction`.
  "
  [f]
  (fn [inputs outputs network]
    (if (concrete-inputs? network inputs)
      (f inputs outputs network)
      [])))

(defn- wire-propagator-edges [g prop-id inputs outputs]
  (let [ins (set inputs)
        outs (set outputs)]
    (reduce (fn [g' in-id] (graph/link-edge g' in-id prop-id))
            (reduce (fn [g' out-id] (graph/link-edge g' prop-id out-id))
                    g
                    outs)
            ins)))

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
                 (assoc-net-prop id (prop (fn [_inputs _outputs network]
                                            (activate inputs outputs network)))))]
       [id n]))))

(defn primitive-propagator
  "Installer for a primitive propagator. Call with node-id tokens (variadic):
  all but the last are inputs, the last is the output cell.

  Example: `((p:id c-in c-out) net)` or `(net/install-net net (p:id c-in c-out))`.

  Primitive functions are allowed to decide how to handle partial/unusable
  inputs themselves. Use `concrete-primitive-propagator` when the primitive
  should not run until every input is usable.
  "
  [f]
  (fn [& node-ids]
    (let [nodes (vec node-ids)
          inputs (vec (butlast nodes))
          output (last nodes)
          activate (fn [input-nodes output-nodes network]
                     (let [input-cells (mapv (partial network-env-lookup network) input-nodes)
                           in-vals (mapv cell/cell-strongest input-cells)]
                       (as-messages output-nodes [(apply f in-vals)])))]
      (construct-propagator activate inputs [output]))))

(def raw-primitive-propagator primitive-propagator)

(defn concrete-primitive-propagator
  "Primitive installer variant that runs only when all inputs are concrete."
  [f]
  (fn [& node-ids]
    (let [nodes (vec node-ids)
          inputs (vec (butlast nodes))
          output (last nodes)
          activate (concrete-propagator
                    (fn [input-nodes output-nodes network]
                      (let [input-cells (mapv (partial network-env-lookup network) input-nodes)
                            in-vals (mapv cell/cell-strongest input-cells)]
                        (as-messages output-nodes [(apply f in-vals)]))))]
      (construct-propagator activate inputs [output]))))

(defn compound-propagator
  "Install a compound propagator wired like any other propagator.

  `closure-in` holds the closure spec; `inputs` / `outputs` are the real boundary cells.
  Activation logic lives in `propagators.closure/compound-activate`."
  [closure-in inputs outputs]
  (let [activate (requiring-resolve 'propagators.closure/compound-activate)]
    (construct-propagator (activate closure-in)
                          (into [closure-in] inputs)
                          outputs)))
