(ns propagators.deprecated.cursor
  "Deprecated pure finite cursor values and accessors.

  This was an experiment for reducing a finite sequence of already-known items.
  It is not the compound-object linked-list reducer path and should not be used
  for recursive nested compound traversal. Prefer public compound-object
  accessors such as `obj/p:car` and `obj/p:cdr` for linked-list work."
  (:refer-clojure :exclude [cons])
  (:require [propagators.cells.value :as value]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def ^:deprecated cursor-key :cursor/cons)
(def ^:deprecated car-key :cursor/car)
(def ^:deprecated cdr-key :cursor/cdr)

(defn ^:deprecated cons
  [item rest]
  {cursor-key true
   car-key item
   cdr-key rest})

(defn ^:deprecated cons?
  [x]
  (and (map? x)
       (true? (get x cursor-key))
       (contains? x car-key)
       (contains? x cdr-key)))

(defn ^:deprecated cursor
  "Build a finite cursor from `items`."
  [items]
  (reduce (fn [rest item] (cons item rest))
          value/nothing
          (reverse items)))

(defn ^:deprecated car-value
  [cursor-value]
  (cond
    (value/nothing? cursor-value) value/nothing
    (value/contradiction? cursor-value) value/contradiction
    (cons? cursor-value) (get cursor-value car-key)
    :else value/contradiction))

(defn ^:deprecated cdr-value
  [cursor-value]
  (cond
    (value/nothing? cursor-value) value/nothing
    (value/contradiction? cursor-value) value/contradiction
    (cons? cursor-value) (get cursor-value cdr-key)
    :else value/contradiction))

(defn ^:deprecated p:car
  [item-id cursor-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     [(message item-id (car-value (net/network-cell-strongest network cursor-id)))])
   [cursor-id]
   [item-id]))

(defn ^:deprecated p:cdr
  [rest-id cursor-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     [(message rest-id (cdr-value (net/network-cell-strongest network cursor-id)))])
   [cursor-id]
   [rest-id]))
