(ns propagators.stdlib.arithmetic.intensity
  (:refer-clojure :exclude [+ - * /])
  (:require [clojure.core :as core]
            [propagators.cells.value :as value]
            [propagators.ids :refer [new-node-id]]
            [propagators.datastructures.compound-object.core :as obj]
            [propagators.layered.procedure :as layer]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def p:sum-intensity
  (prop/primitive-propagator
   :stdlib/intensity-sum
   (fn [_current left right]
     (if (and (number? left) (number? right))
       (core/+ left right)
       value/nothing))))

(defn arithmetic-intensity-closure []
  (layer/argument-layer
   :intensity
   (fn [arguments]
     (boolean (some #(contains? (obj/public-slot-keys %) :intensity) arguments)))
   (fn [arguments]
     (let [values (map #(obj/slot-value % :intensity) arguments)]
       (if (every? number? values)
         (apply core/+ values)
         value/nothing)))))

(def +
  (arithmetic-intensity-closure))

(def -
  (arithmetic-intensity-closure))

(def *
  (arithmetic-intensity-closure))

(def /
  (arithmetic-intensity-closure))
