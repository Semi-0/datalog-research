(ns propagators.cursor
  "Pure finite cursor values and accessors.

  Cursor access is intentionally separate from compound-object accessors: reading
  `car` or `cdr` from `value/nothing` means end-of-cursor and must not create
  compound slot topology."
  (:refer-clojure :exclude [cons])
  (:require [propagators.cells.value :as value]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def cursor-key :cursor/cons)
(def car-key :cursor/car)
(def cdr-key :cursor/cdr)

(defn cons
  [item rest]
  {cursor-key true
   car-key item
   cdr-key rest})

(defn cons?
  [x]
  (and (map? x)
       (true? (get x cursor-key))
       (contains? x car-key)
       (contains? x cdr-key)))

(defn cursor
  "Build a finite cursor from `items`."
  [items]
  (reduce (fn [rest item] (cons item rest))
          value/nothing
          (reverse items)))

(defn car-value
  [cursor-value]
  (cond
    (value/nothing? cursor-value) value/nothing
    (value/contradiction? cursor-value) value/contradiction
    (cons? cursor-value) (get cursor-value car-key)
    :else value/contradiction))

(defn cdr-value
  [cursor-value]
  (cond
    (value/nothing? cursor-value) value/nothing
    (value/contradiction? cursor-value) value/contradiction
    (cons? cursor-value) (get cursor-value cdr-key)
    :else value/contradiction))

(defn p:car
  [item-id cursor-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     [(message item-id (car-value (net/network-cell-strongest network cursor-id)))])
   [cursor-id]
   [item-id]))

(defn p:cdr
  [rest-id cursor-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     [(message rest-id (cdr-value (net/network-cell-strongest network cursor-id)))])
   [cursor-id]
   [rest-id]))
