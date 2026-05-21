(ns ^{:deprecated "Use propagators.cells.cell, .merge, .value, .diff, and .snapshot directly."}
  propagators.cells
  "Deprecated barrel namespace.

  Internal code and tests should require leaf namespaces, e.g.
  `propagators.cells.cell`, `propagators.cells.merge`, `propagators.cells.value`.

  Kept for backward compatibility only."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.diff :as diff]
            [propagators.cells.merge :as merge]
            [propagators.cells.snapshot :as snapshot]
            [propagators.cells.value :as value]))

(def ^{:deprecated true} ->Cell cell/cell)
(def ^{:deprecated true} cell? cell/cell?)
(def ^{:deprecated true} make-cell cell/cell)
(def ^{:deprecated true} cell-snapshot snapshot/cell-snapshot)

(def ^{:deprecated true} nothing value/nothing)
(def ^{:deprecated true} contradiction value/contradiction)
(def ^{:deprecated true} nothing? value/nothing?)
(def ^{:deprecated true} contradiction? value/contradiction?)
(def ^{:deprecated true} value-payload value/value-payload)
(def ^{:deprecated true} cell-value-equal? value/cell-value-equal?)
(def ^{:deprecated true} any-unusable-values? value/any-unusable-values?)

(def ^{:deprecated true} cell-equal? merge/cell-equal?)
(def ^{:deprecated true} generic-merge merge/generic-merge)
(def ^{:deprecated true} cell-merge merge/cell-merge)
(def ^{:deprecated true} cell-updated? merge/cell-updated?)
(def ^{:deprecated true} cell-strongest merge/strongest-value)
(def ^{:deprecated true} handle-contradiction merge/handle-contradiction)

(def ^{:deprecated true} diff-cell diff/diff-cell)
(def ^{:deprecated true} diff-cells diff/diff-cells)

(def ^{:deprecated true} pop-inputs snapshot/pop-inputs)
(def ^{:deprecated true} take-cells snapshot/take-cells)
(def ^{:deprecated true} snapshot-for-id snapshot/snapshot-for-id)
