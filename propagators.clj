(ns propagators
  "Propagator namespace: re-exports `propagators.cell` and `propagators.graph`."
  (:require [propagators.cell :as cell]
            [propagators.graph :as graph]))

;; Cell
(def make-cell cell/make-cell)

;; Graph
(def graph? graph/graph?)
(def empty-graph graph/empty-graph)
(def edges->graph graph/edges->graph)
(def add-edge graph/add-edge)
(def neighbors graph/neighbors)
(def out graph/out)
(def nodes graph/nodes)
