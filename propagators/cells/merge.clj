(ns propagators.cells.merge
  "Merge, strongest selection, and contradiction handling."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]))

(def cell-equal? value/cell-value-equal?)

(defn cell-updated? [new old]
  (not (cell-equal? new old)))

(defmulti cell-merge
  (fn [_content _update] :default))

(defmethod cell-merge :default
  [content update]
  (cond
    (value/nothing? content) update
    (value/nothing? update) content
    (value/contradiction? content) value/contradiction
    (value/contradiction? update) value/contradiction
    (= content update) content
    :else value/contradiction))

(def generic-merge cell-merge)

(defmulti cell-strongest
  (fn [x] (if (cell/cell? x) :cell :content)))

(defmethod cell-strongest :cell
  [c]
  (nth c 2))

(defmethod cell-strongest :content
  [x]
  x)

(defmulti handle-contradiction
  (fn [tasks _node env] [tasks env]))

(defmethod handle-contradiction :default
  [tasks _node env]
  [tasks env])
