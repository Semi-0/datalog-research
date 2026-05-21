(ns propagators.cells.merge
  "Merge, strongest selection, and contradiction handling."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]))

(def cell-equal? value/cell-value-equal?)

(defmulti cell-updated?
  (fn [_new _old _network] :default))

(defmethod cell-updated? :default
  [new old _network]
  (not (cell-equal? new old)))

(defmulti cell-merge
  (fn [_content _update _network] :default))

(defmethod cell-merge :default
  [content update _network]
  (cond
    (value/nothing? content) update
    (value/nothing? update) content
    (value/contradiction? content) value/contradiction
    (value/contradiction? update) value/contradiction
    (= content update) content
    :else value/contradiction))

(def generic-merge cell-merge)

(defmulti strongest-value
  (fn [x _network] (if (cell/cell? x) :cell :content)))

(defmethod strongest-value :cell
  [c _network]
  (nth c 2))

(defmethod strongest-value :content
  [x _network]
  x)

(defmulti handle-contradiction
  (fn [tasks _node env] [tasks env]))

(defmethod handle-contradiction :default
  [tasks _node env]
  [tasks env])
