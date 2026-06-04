(ns propagators.stdlib.arithmetic.provenance
  (:refer-clojure :exclude [+ - * /])
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def p:union
  (prop/primitive-propagator
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
        (let [p:layer @(requiring-resolve 'propagators.layered/p:layer)
              [current arg-a arg-b] input-ids
              [out] output-ids
              a-prov (new-node-id)
              b-prov (new-node-id)
              n1 (reduce net/seed-net-cell network [a-prov b-prov])
              [_ n2] ((p:layer :provenance a-prov arg-a) n1)
              [_ n3] ((p:layer :provenance b-prov arg-b) n2)
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
