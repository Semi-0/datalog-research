(ns propagators.cells.merge
  "Merge, strongest selection, and contradiction handling."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound_subnet :as subnet]))

(def cell-equal? value/cell-value-equal?)

(defonce ^:private compound-merge-loaded*
  (delay (require 'propagators.cells.compound-merge)))

(defn- ensure-compound-merge-loaded!
  []
  (force compound-merge-loaded*))

(defmulti cell-updated?
  (fn [new old _network]
    (if (and (subnet/compound-strongest-result? new)
             (subnet/compound-strongest-result? old))
      (do (ensure-compound-merge-loaded!)
          :compound-strongest)
      :default)))

(defmethod cell-updated? :default
  [new old _network]
  (not (cell-equal? new old)))

(defmulti cell-merge
  (fn [_content update _network]
    (if (subnet/compound-data? update)
      (do (ensure-compound-merge-loaded!)
          :compound-data)
      :default)))

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
  (fn [x _network]
    (cond
      (cell/cell? x) :cell
      (subnet/compound-subnet-state? x) (do (ensure-compound-merge-loaded!)
                                            :compound-subnet)
      :else :content)))

(defmethod strongest-value :cell
  [c _network]
  (cell/cell-strongest c))

(defmethod strongest-value :content
  [x _network]
  x)

(defmulti handle-contradiction
  (fn [tasks _node env] [tasks env]))

(defmethod handle-contradiction :default
  [tasks _node env]
  [tasks env])
