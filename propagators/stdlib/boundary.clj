(ns propagators.stdlib.boundary
  "Boundary topology and bidirectional sync helpers."
  (:require [propagators.network :as net]
            [propagators.stdlib.prop :as prop]))

(defn nothing-out-link
  "Boundary topology: avatar → real (compound output side)."
  [net real avatar]
  (second ((prop/nothing avatar real) net)))

(defn nothing-in-link
  "Boundary topology: real → avatar (compound input side)."
  [net real avatar]
  (second ((prop/nothing real avatar) net)))

(defn bi-sync
  [_closure-struct input-nodes output-nodes network]
  (let [[n-a n-b] (vec input-nodes)
        [out-a out-b] (vec output-nodes)]
    (reduce (fn [n [from to]]
              (second ((prop/id from to) n)))
            network
            [[n-a out-b] [n-b out-a]])))

(def bi-sync-closure
  {:f bi-sync :net net/empty-net})

(defn fast-bi-sync
  "Install bidirectional `prop/id` sync between `a` and `b`.

  Returns `[[a->b b->a] network]`."
  [network a b]
  (let [[a->b n] ((prop/id a b) network)
        [b->a n] ((prop/id b a) n)]
    [[a->b b->a] n]))
