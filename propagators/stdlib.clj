(ns propagators.stdlib
  (:refer-clojure :exclude [partial])
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.snapshot :refer [snap-cell snap-id]]
            [propagators.compile :refer [net-let]]
            [propagators.network :as net]
            [propagators.network :refer [primitive-propagator]]))

(def p:id (primitive-propagator (fn [x] x)))

;; input and output should be the same
(defn bi-sync
  [n input-snapshots output-snapshots]
  (let [in-snap (first input-snapshots)
        out-snap (second input-snapshots)
        input-id (snap-id in-snap)
        output-id (snap-id out-snap)
        in-cell (snap-cell in-snap)
        out-cell (snap-cell out-snap)]
    (net-let n
      [[a input-id (cell/cell-content in-cell) (cell/cell-strongest in-cell)]
       [b output-id (cell/cell-content out-cell) (cell/cell-strongest out-cell)]]
      (p:id a b)
      (p:id b a))))

(def bi-sync-closure [bi-sync net/empty-net])
