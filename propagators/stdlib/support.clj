(ns propagators.stdlib.support
  "Support is a procedure layer, not a gate around layered application."
  (:require [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.layered.procedure :as layer]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def p:combine
  (prop/primitive-propagator
   :stdlib/support-combine
   (fn [_current & arguments]
     ;; Previous output is not an additional input premise. Carrying it here
     ;; would permanently mix an old computation's versions with fresh inputs.
     (apply support/combine (map datum/support-of arguments)))))

(def procedure
  "Whole-datum support combination; previous output is not an input premise."
  (layer/argument-layer
   :support
   (fn [arguments] (boolean (some #(datum/layer-present? % :support) arguments)))
   (fn [arguments] (apply support/combine (map datum/support-of arguments)))))
