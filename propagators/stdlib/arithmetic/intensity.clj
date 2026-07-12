(ns propagators.stdlib.arithmetic.intensity
  (:refer-clojure :exclude [+ - * /])
  (:require [clojure.core :as core]
            [propagators.cells.value :as value]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def p:sum-intensity
  (prop/primitive-propagator
   :stdlib/intensity-sum
   (fn [_current left right]
     (if (and (number? left) (number? right))
       (core/+ left right)
       value/nothing))))

(defn arithmetic-intensity-closure
  "Closure that propagates arithmetic intensity by summing argument intensity."
  []
  {:f (fn [_closure-net input-ids output-ids network]
        (let [p:layer @(requiring-resolve 'propagators.layered/p:layer)
              [current arg-a arg-b] input-ids
              [out] output-ids
              a-intensity (new-node-id)
              b-intensity (new-node-id)
              n1 (reduce net/seed-net-cell network [a-intensity b-intensity])
              [_ n2] ((p:layer :intensity a-intensity arg-a) n1)
              [_ n3] ((p:layer :intensity b-intensity arg-b) n2)
              [_ n4] ((p:sum-intensity current a-intensity b-intensity out) n3)]
          n4))
   :net net/empty-net})

(def +
  (arithmetic-intensity-closure))

(def -
  (arithmetic-intensity-closure))

(def *
  (arithmetic-intensity-closure))

(def /
  (arithmetic-intensity-closure))
