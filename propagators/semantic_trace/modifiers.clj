(ns propagators.semantic-trace.modifiers
  "Legacy entry points contribute supported declarations over shared facts."
  (:require [propagators.cells.value :as value]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.graph-facts :as facts]
            [propagators.semantic-trace.high-level :as high-level]
            [propagators.semantic-trace.tracer-modifiers :as modifiers]))

(defn with-values [graph]
  (when-not (capture/semantic-graph? graph)
    (throw (ex-info "relationship:with-values expects a semantic graph" {})))
  (high-level/derive-view
   (modifiers/apply-modifiers graph [(modifiers/declaration :with-values)])))

(defn caller-view [graph]
  (when-not (capture/semantic-graph? graph)
    (throw (ex-info "relationship:caller-view expects a semantic graph" {})))
  (high-level/derive-view
   (modifiers/apply-modifiers graph [(modifiers/declaration :caller)])))

(defn inherit
  "Value inspection follows expansion. Boundary selection belongs to its scope."
  [graph source]
  (let [source (capture/root-graph source)
        with-values (modifiers/declaration :with-values)
        index (select-keys (modifiers/from-facts source) [with-values])
        legacy (if (and (not (facts/fact-graph? source))
                        (contains? (:graph/modifiers source) :cell-values))
                 (modifiers/normalize-supported [with-values] [value/nothing])
                 (modifiers/normalize-supported [] []))]
    (let [inherited (modifiers/join index legacy)]
      (if (empty? inherited)
        graph
        (high-level/derive-view
         (modifiers/apply-modifiers graph inherited))))))
