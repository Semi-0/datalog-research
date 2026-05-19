(ns propagators.closure
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.diff :refer [diff-cells]]
            [propagators.cells.snapshot :refer [pop-inputs snap-cell snap-id snapshot-for-id take-cells]]
            [propagators.cells.value :as value]
            [propagators.core :refer [run-tasks]]
            [propagators.network :refer [construct-propagator net-env net-graph]]))

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn closure? [x] (tagged? x :closure))
(defn closure [f n] [:closure f n])
(defn closure-f [c] (nth c 1))
(defn closure-net [c] (nth c 2))

(defn apply-network-closure [[f inner-net] input-snapshots output-snapshots]
  (f inner-net input-snapshots output-snapshots))

(defn closure-payload [snap]
  (value/value-payload (cell/cell-strongest (snap-cell snap))))

(defn compound-activate [closure-cell]
  (fn [input-snapshots output-snapshots]
    (let [closure-snap (snapshot-for-id closure-cell input-snapshots)
          closure (closure-payload closure-snap)
          arg-snaps (remove #(= closure-cell (snap-id %)) input-snapshots)
          net (apply-network-closure closure arg-snaps output-snapshots)
          net' (run-tasks (pop-inputs arg-snaps (net-graph net)) net)]
      (diff-cells output-snapshots
                  (take-cells (mapv snap-id output-snapshots)
                              (net-env net')
                              (net-graph net'))))))

(defn compound-propagator
  [closure-cell inputs outputs]
  (construct-propagator (compound-activate closure-cell)
                        (into [closure-cell] inputs)
                        outputs))
