(ns propagators.compiler-2.runtime.operators.graph-modifiers
  "Ordinary result-cell operators for graph presentation modifiers."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.visualization.ttms :as ttms]
            [propagators.datastructures.tms.core :as tms]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.semantic-trace.modifiers :as modifiers]))

(defn- graph-operator [name claim modify]
  (operator/propagator-operator
   {:name name
    :input-selector (fn [arguments _fallback _context]
                      (when-not (= 1 (count arguments))
                        (throw (ex-info (str name " expects one graph") {})))
                      (vec arguments))
    :activate (fn [network [source] [output] _context]
                (let [content (net/network-cell-content network source)
                      graph (data/payload (net/network-cell-strongest network source))]
                  (cond
                    (value/contradiction? graph) [(message output graph)]
                    (value/nothing? graph) (data/state-messages output [content])
                    :else
                    (let [result (modify graph)]
                      [(message output
                                (if (or (ttms/supported? content) (tms/distributed-content? content))
                                  (data/supported [claim output] result #{source} [content])
                                  result))]))))}))

(defn with-values-operator []
  (graph-operator 'relationship:with-values ::with-values modifiers/with-values))

(defn caller-view-operator []
  (graph-operator 'relationship:caller-view ::caller-view modifiers/caller-view))
