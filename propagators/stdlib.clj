(ns propagators.stdlib
  (:refer-clojure :exclude [partial])
  (:require [propagators.cells.cell :as cell]
            [propagators.compile :refer [net-let]]
            [propagators.network :as net]
            [propagators.graph :as g]))

(def p:id (net/primitive-propagator (fn [x] x)))

;; Boundary input/output nodes are the same constraint cells (typically two).
(defn bi-sync
  [closure-struct input-nodes _output-nodes network]
  (let [inner-net (nth closure-struct 2)
        [n-a n-b] (vec input-nodes)
        a-id (g/node-id n-a)
        b-id (g/node-id n-b)
        a-cell (net/network-lookup-cell network n-a)
        b-cell (net/network-lookup-cell network n-b)]
    (net-let inner-net
      [[a a-id (cell/cell-content a-cell) (cell/cell-strongest a-cell)]
       [b b-id (cell/cell-content b-cell) (cell/cell-strongest b-cell)]]
      (p:id a b)
      (p:id b a))))

(def bi-sync-closure [bi-sync net/empty-net])
