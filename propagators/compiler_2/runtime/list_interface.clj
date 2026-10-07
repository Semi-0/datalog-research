(ns propagators.compiler-2.runtime.list-interface
  "Observe list structure without waiting for member values."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]))

(defn list-shape [network list-id]
  (let [candidate (net/network-cell-strongest network list-id)
        head (obj/existing-slot-cell-id network :car list-id)
        tail (obj/existing-slot-cell-id network :cdr list-id)]
    (cond
      (value/contradiction? candidate) :wait
      (and head tail) :cons
      (or head tail) :wait
      (value/nothing? candidate) :wait
      (= :compiler-2/list-empty candidate) :empty
      (obj/accessor-network? candidate) :wait
      :else :scalar)))

(defn list-readiness-effects [key list-id condition-id]
  [(gur/declare-cell condition-id)
   (gur/declare-prop
    (gur/stable-node-id [key :readiness])
    [:compiler-2/list-readiness key] [list-id] [condition-id]
    (fn [_ _ network]
      (let [shape (list-shape network list-id)]
        (if (= :wait shape)
          []
          [(message condition-id shape)]))))])
