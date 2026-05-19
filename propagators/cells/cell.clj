(ns propagators.cells.cell
  (:require [propagators.cells.value :as value]))

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn cell? [x] (tagged? x :cell))
(defn cell [content strongest] [:cell content strongest])
(def ->Cell cell)
(def make-cell cell)

(defn cell-content [c] (nth c 1))
(defn cell-strongest
  "Strongest slot of a `[:cell ...]`, or the cell-value itself."
  [x]
  (cond
    (cell? x) (nth x 2)
    (value/cell-value? x) x
    :else x))
