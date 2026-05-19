(ns propagators.cells.merge
  "Merge and contradiction handling."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]))

(def cell-equal? value/cell-value-equal?)
(def cell-merge value/cell-merge)
(def generic-merge value/cell-merge)
(def cell-updated? value/cell-updated?)
(def cell-strongest cell/cell-strongest)

(defmulti handle-contradiction
  (fn [tasks _node env] [tasks env]))

(defmethod handle-contradiction :default
  [tasks _node env]
  [tasks env])
