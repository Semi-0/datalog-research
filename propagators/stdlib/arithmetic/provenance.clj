(ns propagators.stdlib.arithmetic.provenance
  (:refer-clojure :exclude [+ - * /])
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as obj]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def p:union
  (prop/primitive-propagator
   :stdlib/provenance-union
   (fn [current left right]
     (if (and (set? left) (set? right))
       (set/union
        (if (set? current) current #{})
        left
        right)
       value/nothing))))

(defn arithmetic-provenance-closure
  "Closure that propagates arithmetic provenance by unioning argument provenance."
  []
  {:f (fn [_closure-net input-ids output-ids network]
        (let [[current arg-a arg-b] input-ids
              [out] output-ids
              a-prov (new-node-id)
              b-prov (new-node-id)
              read-provenance
              (prop/primitive-propagator
               :stdlib/argument-provenance
               (fn [argument]
                 (let [provenance (obj/slot-value argument :provenance)
                       base (obj/slot-value argument :base)]
                   (set/union (if (set? provenance) provenance #{})
                              (value/contradiction-provenance base)))))
              n1 (reduce net/seed-net-cell network [a-prov b-prov])
              [_ n2] ((read-provenance arg-a a-prov) n1)
              [_ n3] ((read-provenance arg-b b-prov) n2)
              [_ n4] ((p:union current a-prov b-prov out) n3)]
          n4))
   :net net/empty-net})

(def +
  (arithmetic-provenance-closure))

(def -
  (arithmetic-provenance-closure))

(def *
  (arithmetic-provenance-closure))

(def /
  (arithmetic-provenance-closure))
