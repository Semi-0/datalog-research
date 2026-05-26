(ns propagators.cells.cell
  (:require [propagators.helpers.tagged :refer [tagged?]]))

(def cell? (tagged? :cell))
(defn cell [content strongest] [:cell content strongest])
(def ->Cell cell)
(def make-cell cell)

(defn cell-content [c]
  (nth c 1))

(defn cell-strongest
  "Strongest slot of a `[:cell content strongest]`."
  [c]
  (nth c 2))
