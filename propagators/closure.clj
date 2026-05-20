(ns propagators.closure
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.diff :refer [diff-cells]]
            [propagators.cells.snapshot :refer [pop-inputs snap-cell snap-id snapshot-for-id take-cells]]
            [propagators.cells.value :as value]
            [propagators.core :refer [run-tasks]]
            [propagators.network :refer [construct-propagator net-graph network-cell-strongest]]
            [propagators.graph :as g]
            [propagators.message :as m]))

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn closure? [x] (tagged? x :closure))
;; closure should have network inputs outputs and environment at that timepoint
(defn closure [f n] [:closure f n])
(defn closure-f [c] (nth c 1))
(defn closure-net [c] (nth c 2))

(defn apply-network-closure [[f inner-net] input-nodes output-nodes external-network]
  (f inner-net input-nodes output-nodes external-network))

(defn closure-payload [snap]
  (value/value-payload (cell/cell-strongest (snap-cell snap))))

(defn- boundary-nodes [closure-cell-id nodes]
  (remove #(= closure-cell-id (g/node-id %)) nodes))

(defn compound-activate [closure-in-id closure-out-id]
  (fn [input-nodes output-nodes network]
    (let [closure-cv (network-cell-strongest network closure-in-id)
          closure-payload (value/value-payload closure-cv)
          boundary-in (boundary-nodes closure-in-id input-nodes)
          boundary-out (boundary-nodes closure-out-id output-nodes)
          in-vals (mapv #(network-cell-strongest network %) boundary-in)]
      (if (or (value/unusable? closure-cv)
              (value/any-unusable-values? in-vals)
              (nil? closure-payload))
        []
        (let [cf (closure-f closure-payload)
              net (apply-network-closure closure-payload boundary-in boundary-out network)
              net' (run-tasks (pop-inputs boundary-in (net-graph net)) net)
              closure-struct' (closure cf net')]
          (into (diff-cells boundary-out net' network)
                [(m/message closure-out-id closure-struct')]))))))

(defn compound-propagator
  [closure-in closure-out inputs outputs]
  (construct-propagator (compound-activate closure-in closure-out)
                        (into [closure-in] inputs)
                        (into [closure-out] outputs)))
