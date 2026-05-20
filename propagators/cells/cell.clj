(ns propagators.cells.cell)

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn cell? [x] (tagged? x :cell))
(defn cell [content strongest] [:cell content strongest])
(def ->Cell cell)
(def make-cell cell)

(defn cell-content [c] (nth c 1))
