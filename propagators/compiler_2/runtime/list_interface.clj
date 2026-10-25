(ns propagators.compiler-2.runtime.list-interface
  "Observe list structure without waiting for member values."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.stdlib.premise-state :as state]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]))

(defn list-shape [network list-id]
  (let [current (net/network-cell-strongest network list-id)
        candidate (if (datum/layer-present? current :base)
                    (datum/layer-value current :base) current)
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
      (let [shape (list-shape network list-id)
            current (net/network-cell-strongest network list-id)]
        (if (datum/layer-present? current :support)
          [(message condition-id
                    (collection/content
                     {:base (if (= :wait shape) value/nothing shape)
                      :support (datum/support-of current)
                      :premise-state (state/states-of current)}))]
          (if (= :wait shape)
            []
            [(message condition-id shape)])))))])
