(ns propagators
  "Propagator namespace: re-exports cell, graph, and related modules."
  (:require [propagators.cell :as cell]
            [propagators.cell-merge :as merge]
            [propagators.cell-value :as value]
            [propagators.graph :as graph]
            [propagators.propagator :as propagator]))

;; Cell
(def make-cell cell/make-cell)
(def cell? cell/cell?)
(def cell-snapshot cell/cell-snapshot)

;; Cell value
(def ->CellValue value/->CellValue)
(def nothing value/nothing)
(def contradiction value/contradiction)
(def partial value/partial)
(def complete value/complete)
(def nothing? value/nothing?)
(def contradiction? value/contradiction?)
(def partial? value/partial?)
(def complete? value/complete?)
(def value-payload value/value-payload)

;; Cell merge
(def cell-merge merge/cell-merge)
(def generic-merge merge/generic-merge)
(def cell-strongest merge/cell-strongest)
(def cell-equal? merge/cell-equal?)
(def cell-updated? merge/cell-updated?)
(def handle-contradiction merge/handle-contradiction)

;; Propagator
(def ->Propagator propagator/->Propagator)
(def make-propagator propagator/make-propagator)
(def propagator? propagator/propagator?)
(def Propagator? propagator/propagator?)

;; Graph
(def graph? graph/graph?)
(def node? graph/node?)
(def node graph/node)
(def make-graph graph/make-graph)
(def empty-graph graph/empty-graph)
(def edges->graph graph/edges->graph)
(def add-node graph/add-node)
(def add-edge graph/add-edge)
(def node-ids graph/node-ids)
(def nodes graph/nodes)
(def get-node graph/get-node)
(def node-inputs graph/node-inputs)
(def node-outputs graph/node-outputs)
(def in graph/in)
(def predecessors graph/predecessors)
(def out graph/out)
(def neighbors graph/neighbors)
(def update-node graph/update-node)
(def map-nodes graph/map-nodes)
(def edges graph/edges)
