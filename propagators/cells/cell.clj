(ns propagators.cells.cell)

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn cell? [x] (tagged? x :cell))
(defn cell [content strongest] [:cell content strongest])
(def ->Cell cell)
(def make-cell cell)

(defn cell-content [c] (nth c 1))
(defn cell-strongest
  "Strongest slot of a `[:cell ...]`, or the content itself."
  [x]
  (if (cell? x) (nth x 2) x))
