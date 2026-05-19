(ns propagators.closure
  "Stored network closure: `[f [graph env]]` in a cell, activated at compound boundary."
  (:require [propagators.cells.diff :refer [diff-cells]]
            [propagators.cells.snapshot :refer [pop-inputs snapshot-for-id take-cells]]
            [propagators.cells.value :refer [value-payload]]
            [propagators.core :refer [run-tasks]]))

(defn apply-network-closure [[f [graph env]] arg-snapshots]
  (f graph env arg-snapshots))

(defn closure-payload [[_ cell]]
  (value-payload (:strongest cell)))

;; maybe we should simply feeds in a inputs and outputs pointer 
;; and a env(network) for the propagaor
;; and network inside just look it up
;; problem is we do not have native bi-directional binding
;; without we have a compound propagator like this first
;; so we have to return the message rather than set the environment
(defn compound-activate [closure-cell]
  (fn [input-snapshots output-snapshots]
    (let [closure-snap (snapshot-for-id closure-cell input-snapshots)
          closure (closure-payload closure-snap)
          arg-snaps (remove #(= closure-cell (:id (first %))) input-snapshots)
          [graph env] (apply-network-closure closure (concat arg-snaps output-snapshots))
          network (run-tasks (pop-inputs input-snapshots graph) [graph env])]
      (diff-cells output-snapshots
                  (take-cells (mapv first output-snapshots) network)))))
