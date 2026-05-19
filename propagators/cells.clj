(ns propagators.cells
  (:refer-clojure :exclude [partial])
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.diff :as diff]
            [propagators.cells.merge :as merge]
            [propagators.cells.snapshot :as snapshot]
            [propagators.cells.value :as value]))

(def ->Cell cell/cell)
(def cell? cell/cell?)
(def make-cell cell/cell)
(def cell-snapshot snapshot/cell-snapshot)

(def ->CellValue value/cell-value)
(def cell-value? value/cell-value?)
(def nothing value/nothing)
(def contradiction value/contradiction)
(def partial value/partial)
(def complete value/complete)
(def nothing? value/nothing?)
(def contradiction? value/contradiction?)
(def partial? value/partial?)
(def complete? value/complete?)
(def value-payload value/value-payload)
(def cell-value-equal? value/cell-value-equal?)
(def any-unusable-values? value/any-unusable-values?)

(def cell-equal? value/cell-value-equal?)
(def generic-merge value/cell-merge)
(def cell-merge value/cell-merge)
(def cell-updated? value/cell-updated?)
(def cell-strongest cell/cell-strongest)
(def handle-contradiction merge/handle-contradiction)

(def diff-cell diff/diff-cell)
(def diff-cells diff/diff-cells)

(def pop-inputs snapshot/pop-inputs)
(def take-cells snapshot/take-cells)
(def snapshot-for-id snapshot/snapshot-for-id)
