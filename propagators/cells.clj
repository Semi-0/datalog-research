(ns propagators.cells
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.diff :as diff]
            [propagators.cells.merge :as merge]
            [propagators.cells.snapshot :as snapshot]
            [propagators.cells.value :as value]))

(def ->Cell cell/cell)
(def cell? cell/cell?)
(def make-cell cell/cell)
(def cell-snapshot snapshot/cell-snapshot)

(def nothing value/nothing)
(def contradiction value/contradiction)
(def nothing? value/nothing?)
(def contradiction? value/contradiction?)
(def value-payload value/value-payload)
(def cell-value-equal? value/cell-value-equal?)
(def any-unusable-values? value/any-unusable-values?)

(def cell-equal? merge/cell-equal?)
(def generic-merge merge/generic-merge)
(def cell-merge merge/cell-merge)
(def cell-updated? merge/cell-updated?)
(def cell-strongest merge/strongest-value)
(def handle-contradiction merge/handle-contradiction)

(def diff-cell diff/diff-cell)
(def diff-cells diff/diff-cells)

(def pop-inputs snapshot/pop-inputs)
(def take-cells snapshot/take-cells)
(def snapshot-for-id snapshot/snapshot-for-id)
