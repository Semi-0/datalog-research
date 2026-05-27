(ns propagators.datastructures.compound_subnet_state
  "Compound subnet content as a network extension map (`:out-ids` slot)."
  (:require [propagators.network :as net]))

(def ^:private extra-keys [:out-ids :updated*])

(defn compound-state
  "Extend `subnet` with tracked outer output ids."
  [subnet out-ids]
  (assoc subnet :out-ids out-ids))

(defn state-subnet
  "Network view of compound state (extra slots stripped)."
  [state]
  (apply dissoc state extra-keys))

(defn state-out-ids
  "Outer ids for dispatch/strongest runs; default `#{}` when absent."
  [state]
  (or (:out-ids state) #{}))

(defn compound-subnet-state?
  "Network-shaped map with compound `:out-ids` slot."
  [x]
  (and (net/network? x)
       (contains? x :out-ids)
       (set? (state-out-ids x))))

(defn empty-compound-subnet
  "Initial compound cell content before any merge."
  []
  (compound-state net/empty-net #{}))
