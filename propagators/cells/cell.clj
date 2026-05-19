(ns propagators.cells.cell
  "Propagator cell record and snapshot."
  (:refer-clojure :exclude [partial])
  (:require [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]))

(defrecord Cell [content strongest])

(defn cell? [x]
  (instance? Cell x))

(defn make-cell [content strongest]
  (->Cell content strongest))

(defn cell-snapshot
  "`(cell-snapshot env)` returns `node → [node cell]` for each input node."
  [env]
  (fn [node]
    [node (get env (:id node))]))

;; --- re-exports: cell value ---
(def ->CellValue value/->CellValue)
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

;; --- re-exports: cell merge ---
(def cell-equal? merge/cell-equal?)
(def generic-merge merge/generic-merge)
(def cell-merge merge/cell-merge)
(def cell-updated? merge/cell-updated?)
(def cell-strongest merge/cell-strongest)
(def handle-contradiction merge/handle-contradiction)
