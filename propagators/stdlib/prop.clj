(ns propagators.stdlib.prop
  "Primitive propagator installers (`prop/+`, `prop/id`, …)."
  (:refer-clojure :exclude [+ - * / <= not])
  (:require [clojure.core :as core]
            [propagators.cells.bool4 :as bool4]
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

(def <=
  (prop/primitive-propagator
   (fn [a b]
     (cond
       (core/or (value/contradiction? a)
                (value/contradiction? b))
       value/contradiction

       (core/or (value/nothing? a)
                (value/nothing? b))
       value/nothing

       :else
       (try
         (core/<= a b)
         (catch Exception _
           value/contradiction))))))

(def not
  (prop/primitive-propagator bool4/not))

(def switch
  (prop/primitive-propagator
   (fn [x enabled?]
     (cond
       (value/contradiction? enabled?) value/contradiction
       (= true enabled?) x
       :else value/nothing))))

(defn nothing
  "Topology-only link: wires ports without merge/messages when the propagator runs."
  [a b]
  (prop/construct-propagator (fn [_inputs _outputs _network] []) [a] [b]))
