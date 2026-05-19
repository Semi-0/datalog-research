(ns propagators.cells.snapshot
  "Cell snapshots and wake helpers for compound / boundary wiring."
  (:require [propagators.cells.cell :as cell]
            [propagators.graph :refer [node-outputs]]))

(defn pop-inputs [snapshots graph]
  (mapcat (fn [[node _]] (node-outputs graph node)) snapshots))

(defn take-cells [nodes [_ env]]
  (map (fn [node] ((cell/cell-snapshot env) node)) nodes))

(defn snapshot-for-id [node-id snapshots]
  (some (fn [[node :as snap]]
          (when (= node-id (:id node)) snap))
        snapshots))
