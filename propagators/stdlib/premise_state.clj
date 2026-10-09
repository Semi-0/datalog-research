(ns propagators.stdlib.premise-state
  "An ordinary procedure layer for state knowledge, not result dependencies."
  (:require [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.layered.procedure :as layer]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn states-of [value]
  (support/join
   (datum/support-of value)
   (if (datum/layer-present? value :premise-state)
     (datum/layer-value value :premise-state)
     #{})))

(def p:join
  (prop/primitive-propagator
   :stdlib/premise-state
   (fn [_current & arguments]
     (apply support/join (map states-of arguments)))))

(def procedure
  (layer/argument-layer
   :premise-state
   (fn [arguments]
     (boolean (some #(datum/layer-present? % :premise-state) arguments)))
   (fn [arguments] (apply support/join (map states-of arguments)))))
