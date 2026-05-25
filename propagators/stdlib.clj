(ns propagators.stdlib
  (:require [propagators.cells.cell :as cell]
            [propagators.compile :refer [net-let]]
            [propagators.network :as net]
            [propagators.cells.value :as val]))

(def p:id (net/primitive-propagator (fn [x] x)))
(def p:nothing (net/primitive-propagator (fn [x] val/nothing)))

;; Topology-only boundary link: no messages when the propagator runs.
(def p:nothing-b (fn [a b] (net/construct-propagator (fn [_inputs _outputs _network] []) [a] [b])))

;; Boundary input/output nodes are the same constraint cells (typically two).
;; Cross-sync: each input boundary feeds the opposite output (avatar) port via `p:id`.
(defn bi-sync
  [_closure-struct input-nodes output-nodes network]
  (let [[n-a n-b] (vec input-nodes)
        [out-a out-b] (vec output-nodes)]
    (reduce (fn [n [from to]]
              (second ((p:id from to) n)))
            network
            [[n-a out-b] [n-b out-a]])))

(def bi-sync-closure [bi-sync net/empty-net])
