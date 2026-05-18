(ns propagators.cell
  "Propagator cell: multiset `content` plus `strongest` cell value.

  Value lattice: `propagators.cell-value`
  Merge / update: `propagators.cell-merge`"
  (:refer-clojure :exclude [partial])
  (:require [propagators.cell-merge :as merge]
            [propagators.cell-value :as value]))

(defrecord Cell [content strongest])

(defn cell?
  [x]
  (instance? Cell x))

(defn make-cell
  ([content strongest]
   (->Cell content strongest)))

(defn cell-snapshot
  "`(cell-snapshot env)` returns `node-id → [node cell]` for each input id."
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

;; --- re-exports: cell merge ---
(def cell-equal? merge/cell-equal?)
(def generic-merge merge/generic-merge)
(def cell-merge merge/cell-merge)
(def cell-updated? merge/cell-updated?)
(def cell-strongest merge/cell-strongest)
(def handle-contradiction merge/handle-contradiction)
