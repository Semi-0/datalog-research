(ns propagators.cell
  "Propagator cell identity (usable as a graph node).")

(defrecord Cell [id])

(defn make-cell [id]
  (->Cell id))
