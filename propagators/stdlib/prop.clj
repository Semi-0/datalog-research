(ns propagators.stdlib.prop
  "Primitive propagator installers (`prop/+`, `prop/id`, …)."
  (:refer-clojure :exclude [+ - * / <= not and or when])
  (:require [clojure.core :as core]
            [propagators.cells.bool4 :as bool4]
            [propagators.cells.value :as value]
            [propagators.message :refer [message]]
            [propagators.network :as net]
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

(def and
  (prop/primitive-propagator bool4/and))

(def or
  (prop/primitive-propagator bool4/or))

(def nothing?
  (prop/primitive-propagator
   (fn [x]
     (cond
       (value/contradiction? x) value/contradiction
       (value/nothing? x) true
       :else false))))

(def switch
  (prop/primitive-propagator
   (fn [x enabled?]
     (cond
       (value/contradiction? enabled?) value/contradiction
       (= true enabled?) x
       :else value/nothing))))

(defn when
  "One-armed value gate. Emits `value-id` to `out-id` only when condition is true."
  [value-id condition-id out-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [v (net/network-cell-strongest network value-id)
           condition (net/network-cell-strongest network condition-id)]
       (cond
         (value/contradiction? condition)
         [(message out-id value/contradiction)]

         (= true condition)
         [(message out-id v)]

         (core/or (= false condition)
                  (value/nothing? condition))
         []

         :else
         [(message out-id value/contradiction)])))
   [value-id condition-id]
   [out-id]))

(defn nothing
  "Topology-only link: wires ports without merge/messages when the propagator runs."
  [a b]
  (prop/construct-propagator (fn [_inputs _outputs _network] []) [a] [b]))
