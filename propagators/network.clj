(ns propagators.network
  "Network constructors: cells and propagators."
  (:require [as-messages :as h]
            [propagators.cells.value :refer [any-unusable-values?]]
            [propagators.closure :refer [compound-activate]]
            [propagators.graph :refer [node]]
            [propagators.ids :refer [new-node-id]]
            [propagators.propagator :refer [make-propagator]]))

;; propagator needs unified interface
;; Both `construct-propagator` and `compound-propagator` share:
;;   (activate inputs outputs) -> installer
;;   installer = (fn [[graph env]] -> [prop-id [graph env]])

(defn construct-cell
  "Install a cell. No args: fresh `java.util.UUID` v7 id. One arg: use given `id`."
  ([]
   (construct-cell (new-node-id)))
  ([id]
   (fn [[graph env]]
     [id [graph (h/cell-slot id env)]])))

(defn construct-propagator
  "Install propagator with `activate`. Wires input cells → propagator → output cells.
  `(construct-propagator f inputs outputs)` → installer."
  ([activate inputs outputs]
   (construct-propagator (new-node-id) activate inputs outputs))
  ([id activate inputs outputs]
   (fn [[graph env]]
     (let [ins (set inputs)
           outs (set outputs)
           graph (-> graph
                     (assoc id (node id ins outs))
                     (h/wire-propagator-edges id ins outs))]
       [id [graph (assoc env id (make-propagator activate))]]))))

(defn primitive-propagator
  "`(primitive-propagator f)` → installer `(fn [args] …)` where
  `args` = `[in1 … out]`. `f` receives strongest `CellValue`s from inputs."
  [f]
  (fn [args]
    (let [inputs (vec (butlast args))
          output (last args)
          wrapped-f (fn [input-snapshots output-snapshots]
                      (let [in-vals (mapv h/strongest-from-snapshot input-snapshots)]
                        (if (any-unusable-values? in-vals)
                          []
                          (h/as-messages output-snapshots [(apply f in-vals)]))))]
      (construct-propagator wrapped-f inputs [output]))))

;; the diff algorithm would large influence the performance of overall network
;; or the accuracy
(defn compound-propagator
  "Install compound propagator. `closure-cell` holds `[f [graph env]]` in `:strongest`.
  `(compound-propagator closure-cell inputs outputs)` → installer (same as `construct-propagator`)."
  [closure-cell inputs outputs]
  (construct-propagator (compound-activate closure-cell)
                        (into [closure-cell] inputs)
                        outputs))
