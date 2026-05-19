(ns propagators
  "Propagator namespace: re-exports cells, graph, and related modules."
  (:require [propagators.cells :as cells]
            [propagators.graph :as graph]
            [propagators.propagator :as propagator]))

;; Cell
(def make-cell cells/make-cell)
(def cell? cells/cell?)
(def cell-snapshot cells/cell-snapshot)

;; Cell value
(def ->CellValue cells/->CellValue)
(def nothing cells/nothing)
(def contradiction cells/contradiction)
(def partial cells/partial)
(def complete cells/complete)
(def nothing? cells/nothing?)
(def contradiction? cells/contradiction?)
(def partial? cells/partial?)
(def complete? cells/complete?)
(def value-payload cells/value-payload)

;; Cell merge
(def cell-merge cells/cell-merge)
(def generic-merge cells/generic-merge)
(def cell-strongest cells/cell-strongest)
(def cell-equal? cells/cell-equal?)
(def cell-updated? cells/cell-updated?)
(def handle-contradiction cells/handle-contradiction)

;; Propagator
(def ->Propagator propagator/->Propagator)
(def make-propagator propagator/make-propagator)
(def propagator? propagator/propagator?)

;; Graph
(def graph? graph/graph?)
(def node? graph/node?)
(def node graph/node)
(def get-node graph/get-node)
(def node-inputs graph/node-inputs)
(def node-outputs graph/node-outputs)
(def link-edge graph/link-edge)
