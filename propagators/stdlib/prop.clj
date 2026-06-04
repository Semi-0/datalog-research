(ns propagators.stdlib.prop
  "Primitive propagator installers (`prop/+`, `prop/id`, …)."
  (:refer-clojure :exclude [+ - * /])
  (:require [clojure.core :as core]
            [propagators.cells.value :as value]
            [propagators.propagator :as prop]))

(def id
  (prop/primitive-propagator (fn [x] x)))

(def +
  (prop/primitive-propagator core/+))

(def -
  (prop/primitive-propagator core/-))

(def *
  (prop/primitive-propagator core/*))

(def /
  (prop/primitive-propagator core//))

(def switch
  (prop/primitive-propagator
   (fn [x enabled?]
     (if enabled? x value/nothing))))

(defn nothing
  "Topology-only link: wires ports without merge/messages when the propagator runs."
  [a b]
  (prop/construct-propagator (fn [_inputs _outputs _network] []) [a] [b]))
