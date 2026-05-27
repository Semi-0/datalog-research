(ns propagators.datastructures.compound_strongest_result
  "Compound subnet strongest-result shape and accessors."
  (:require [propagators.network :as net]))

(defrecord CompoundStrongestResult [subnet updated*])

(defn strongest-result [subnet updated*]
  (->CompoundStrongestResult subnet updated*))

(defn strongest-subnet [result]
  (:subnet result))

(defn strongest-updated* [result]
  (:updated* result))

(defn compound-strongest-result?
  "Strongest slot shape after effectful run."
  [x]
  (and (map? x)
       (contains? x :subnet)
       (contains? x :updated*)
       (net/network? (strongest-subnet x))
       (instance? clojure.lang.Atom (strongest-updated* x))))
