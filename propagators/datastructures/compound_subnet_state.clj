(ns propagators.datastructures.compound_subnet_state
  "Compound subnet content-state shape and accessors."
  (:require [propagators.network :as net]))

(defrecord CompoundSubnetState [subnet out-ids])

(defn compound-state [subnet out-ids]
  (->CompoundSubnetState subnet out-ids))

(defn state-subnet [state]
  (:subnet state))

(defn state-out-ids [state]
  (:out-ids state))

(defn compound-subnet-state?
  "Cell content shape with accumulated outer output ids."
  [x]
  (and (map? x)
       (contains? x :subnet)
       (contains? x :out-ids)
       (net/network? (state-subnet x))
       (set? (state-out-ids x))))

(defn empty-compound-subnet
  "Initial compound cell content before any merge."
  []
  (compound-state net/empty-net #{}))
