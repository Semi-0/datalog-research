(ns propagators.stdlib.arithmetic.base
  (:require [propagators.network :as net]
            [propagators.stdlib.prop :as prop]))

(defn arithmetic-base-closure
  "Closure that applies primitive arithmetic to base-layer inputs."
  [primitive-propagator]
  {:f (fn [_closure-net input-ids output-ids network]
        (let [[a b] input-ids
              [out] output-ids]
          (second ((primitive-propagator a b out) network))))
   :net net/empty-net})

(def plus-closure
  (arithmetic-base-closure prop/+))

(def minus-closure
  (arithmetic-base-closure prop/-))

(def times-closure
  (arithmetic-base-closure prop/*))

(def divide-closure
  (arithmetic-base-closure prop//))
