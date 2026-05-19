(ns propagators.cells
  "Cell API barrel: re-exports from `propagators.cells.*`."
  (:refer-clojure :exclude [partial])
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.diff :as diff]
            [propagators.cells.snapshot :as snapshot]))

(def ->Cell cell/->Cell)
(def cell? cell/cell?)
(def make-cell cell/make-cell)
(def cell-snapshot cell/cell-snapshot)

(def ->CellValue cell/->CellValue)
(def cell-value? cell/cell-value?)
(def nothing cell/nothing)
(def contradiction cell/contradiction)
(def partial cell/partial)
(def complete cell/complete)
(def nothing? cell/nothing?)
(def contradiction? cell/contradiction?)
(def partial? cell/partial?)
(def complete? cell/complete?)
(def value-payload cell/value-payload)
(def cell-value-equal? cell/cell-value-equal?)
(def any-unusable-values? cell/any-unusable-values?)

(def cell-equal? cell/cell-equal?)
(def generic-merge cell/generic-merge)
(def cell-merge cell/cell-merge)
(def cell-updated? cell/cell-updated?)
(def cell-strongest cell/cell-strongest)
(def handle-contradiction cell/handle-contradiction)

(def diff-cell diff/diff-cell)
(def diff-cells diff/diff-cells)

(def pop-inputs snapshot/pop-inputs)
(def take-cells snapshot/take-cells)
(def snapshot-for-id snapshot/snapshot-for-id)
