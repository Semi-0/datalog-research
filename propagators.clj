(ns propagators
  "Propagator namespace: re-exports graph and cell primitives from leaf modules."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.snapshot :as snapshot]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.propagator :as propagator]))

;; Cell
(def make-cell cell/cell)
(def cell? cell/cell?)
(def cell-snapshot snapshot/cell-snapshot)

;; Cell value
(def nothing value/nothing)
(def contradiction value/contradiction)
(def nothing? value/nothing?)
(def contradiction? value/contradiction?)
(def value-payload value/value-payload)

;; Cell merge
(def cell-merge merge/cell-merge)
(def generic-merge merge/generic-merge)
(def cell-strongest merge/strongest-value)
(def cell-equal? merge/cell-equal?)
(def cell-updated? merge/cell-updated?)
(def handle-contradiction merge/handle-contradiction)

;; Propagator
(def ->Propagator propagator/->Propagator)
(def make-propagator propagator/make-propagator)
(def propagator? propagator/propagator?)
(def construct-propagator propagator/construct-propagator)
(def primitive-propagator propagator/primitive-propagator)
(def compound-propagator propagator/compound-propagator)

;; Graph
(def graph? graph/graph?)
(def node? graph/node?)
(def node graph/node)
(def get-node graph/get-node)
(def node-inputs graph/node-inputs)
(def node-outputs graph/node-outputs)
(def link-edge graph/link-edge)
