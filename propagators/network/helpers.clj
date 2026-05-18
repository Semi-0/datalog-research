(ns propagators.network.helpers
  (:require [propagators.cell :refer [->Cell nothing]]
            [propagators.graph :refer [link-edge]]))

(defn cell-slot
  [id env]
  (assoc env id (->Cell nothing nothing)))

(defn make-message
  [node message]
  [node message])

(defn strongest-from-snapshot
  "From `cell-snapshot` pair `[node cell]` return the cell's `:strongest` `CellValue`."
  [[_node {:keys [strongest]}]]
  strongest)

(defn as-message
  "Pair each output snapshot `[node cell]` with each value in `values` (a vector)."
  [output-snaps values]
  (let [nodes (mapv first output-snaps)]
    (when (not= (count nodes) (count values))
      (throw (ex-info "output count mismatch"
                      {:outputs (count nodes) :results (count values)})))
    (mapv make-message nodes values)))

(defn wire-propagator-edges
  "Link each input cell → propagator and propagator → each output cell."
  [graph prop-id inputs outputs]
  (let [ins (set inputs)
        outs (set outputs)]
    (reduce (fn [g in-id] (link-edge g in-id prop-id))
            (reduce (fn [g out-id] (link-edge g prop-id out-id))
                    graph
                    outs)
            ins)))
