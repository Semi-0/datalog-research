(ns propagators.datastructures.compound_strongest_result
  "Compound subnet continuation as a network extension map (`:updated*` slot)."
  (:require [propagators.network :as net]))

(def ^:private extra-keys [:out-ids :updated*])

(defn subnet-continuation
  "Extend `subnet` with effectful-run change frontier and optional `:out-ids`."
  ([subnet updated*]
   (assoc subnet :updated* updated*))
  ([subnet updated* out-ids]
   (assoc subnet :updated* updated* :out-ids out-ids)))

(defn continuation-subnet
  "Network view of continuation (extra slots stripped)."
  [continuation]
  (apply dissoc continuation extra-keys))

(defn continuation-updated*
  "Ids changed in the last effectful step; default `(atom #{})` when absent."
  [continuation]
  (or (:updated* continuation) (atom #{})))

(defn continuation-out-ids
  "Tracked outer output ids carried from compound state; default `#{}` when absent."
  [continuation]
  (or (:out-ids continuation) #{}))

(defn compound-subnet-continuation?
  "Network-shaped map with compound `:updated*` slot."
  [x]
  (and (net/network? x)
       (contains? x :updated*)
       (instance? clojure.lang.Atom (continuation-updated* x))))
