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
                 (assoc-net-prop id (prop activate)))]
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

(defn compound-propagator
  "Install a compound propagator wired like any other propagator.

  `closure-in` holds the closure spec; `inputs` / `outputs` are the real boundary cells.
  Activation logic lives in `propagators.closure/compound-activate`."
  [closure-in inputs outputs]
  (let [activate (requiring-resolve 'propagators.closure/compound-activate)]
    (construct-propagator (activate closure-in)
                          (into [closure-in] inputs)
                          outputs)))
