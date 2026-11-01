(ns propagators.semantic-trace.modifiers
  "Legacy entry points now contribute monotonic view declarations."
  (:require [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.graph-facts :as facts]
            [propagators.semantic-trace.tracer-modifiers :as modifiers]))

(defn with-values [graph]
  (when-not (capture/semantic-graph? graph)
    (throw (ex-info "relationship:with-values expects a semantic graph" {})))
  (modifiers/apply-modifiers graph [(modifiers/declaration :with-values)]))

(defn caller-view [graph]
  (when-not (capture/semantic-graph? graph)
    (throw (ex-info "relationship:caller-view expects a semantic graph" {})))
  (modifiers/apply-modifiers graph [(modifiers/declaration :caller)]))

(defn inherit [graph source]
  (let [source (capture/root-graph source)
        declarations (facts/declarations source)]
    (modifiers/apply-modifiers graph
                               (cond-> declarations
                                 (contains? (:graph/modifiers source) :cell-values)
                                 (conj (modifiers/declaration :with-values))))))
