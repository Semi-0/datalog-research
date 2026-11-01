(ns propagators.compiler-2.runtime.operators.high-level-trace
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.experimental.visualization.data :as data]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.semantic-trace.high-level :as high-level]))

(defn high-level-operator []
  (operator/propagator-operator
   {:name 'relationship:high-level
    :input-selector
    (fn [arguments _fallback _context]
      (when (< (count arguments) 2)
        (throw (ex-info "relationship:high-level expects a graph and explicit boundaries" {})))
      (vec arguments))
    :activate
    (fn [network sources [output] _context]
      (let [contents (mapv #(net/network-cell-content network %) sources)
            inputs (mapv #(data/payload (net/network-cell-strongest network %)) sources)
            contradiction (some value/contradiction? inputs)]
        (cond
          contradiction
          [(message output (data/supported [::high-level output] value/contradiction
                                            (set sources) contents))]

          (some value/nothing? inputs)
          (data/state-messages output contents)

          :else
          [(message output (data/supported [::high-level output]
                                            (high-level/view (first inputs) (rest inputs))
                                            (set sources) contents))])))}))
