(ns propagators.datastructures.compound-object.sequence
  "Cons-style compound-object helpers."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as core]
            [propagators.datastructures.compound-object.slot :as slot]
            [propagators.network-builder :as nb]))

(defn empty-cons-net
  "A named network with stable `:car` and `:cdr` slot cells."
  []
  (-> (core/empty-compound-object)
      (core/add-slot-cell :car value/nothing)
      (core/add-slot-cell :cdr value/nothing)))

(defn ensure-cons-net
  [x]
  (core/compound-object x))

(defn p:car [elem-id collection-id]
  (slot/p:slot :car elem-id collection-id))

(defn p:cdr [elem-id collection-id]
  (slot/p:slot :cdr elem-id collection-id))

(defn p:cons
  [head-id tail-id collection-id]
  (fn [network]
    (let [[car-prop n] (nb/install-propagator network (p:car head-id collection-id))
          [cdr-prop n] (nb/install-propagator n (p:cdr tail-id collection-id))]
      [[car-prop cdr-prop] n])))
