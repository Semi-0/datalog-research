(ns propagators.network.constructors
  (:require [propagators.cell-value :refer [any-unusable-values?]]
            [propagators.graph :refer [node]]
            [propagators.ids :refer [new-node-id]]
            [propagators.network.helpers :as h]
            [propagators.propagator :refer [make-propagator]]))

(defn construct-cell
  "Install a cell. No args: fresh `java.util.UUID` v7 id. One arg: use given `id`."
  ([]
   (construct-cell (new-node-id)))
  ([id]
   (fn [[graph env]]
     [id [graph (h/cell-slot id env)]])))

(defn construct-propagator
  "Install propagator with `f`. No `id` arg: fresh v7 id. `inputs` / `outputs` are node id sets (or seqs).
  Wires input cells → propagator → output cells in `graph`."
  ([f inputs outputs]
   (construct-propagator (new-node-id) f inputs outputs))
  ([id f inputs outputs]
   (fn [[graph env]]
     (let [ins (set inputs)
           outs (set outputs)
           graph (-> graph
                     (assoc id (node id ins outs))
                     (h/wire-propagator-edges id ins outs))]
       [id [graph (assoc env id (make-propagator f))]])))
  ([f]
   (fn [inputs outputs]
     (construct-propagator f inputs outputs))))

(defn primitive-propagator
  "`(primitive-propagator f)` → installer `(fn [args] …)` where
  `args` = `[in1 … out]`. `f` receives strongest `CellValue`s from inputs."
  [f]
  (fn [args]
    (let [inputs (vec (butlast args))
          output (last args)
          wrapped-f (fn [in-snaps out-snaps]
                      (let [in-vals (mapv h/strongest-from-snapshot in-snaps)]
                        (if (any-unusable-values? in-vals)
                          []
                          (h/as-message out-snaps [(apply f in-vals)]))))]
      (construct-propagator wrapped-f inputs [output]))))
