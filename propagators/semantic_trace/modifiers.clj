(ns propagators.semantic-trace.modifiers
  "Composable presentation requests over immutable tracer output."
  (:require [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.capture :as capture]))

(defn- add-modifier
  [graph modifier]
  (update graph :graph/modifiers (fnil conj #{}) modifier))

(defn with-values [graph]
  (when-not (capture/semantic-graph? graph)
    (throw (ex-info "relationship:with-values expects a semantic graph" {})))
  (if (capture/fragment? graph)
    (capture/update-scope graph :root #(add-modifier % :cell-values))
    (add-modifier graph :cell-values)))

(defn- caller-graph [graph]
  (if (and (capture/semantic-graph? graph) (seq (:dataflow/seeds graph)))
    (let [excluded (:dataflow/observer-applications graph #{})
          edges (remove (fn [[source target]] (or (excluded source) (excluded target))) (:edges graph))
          cut (assoc graph :edges (vec edges))
          components (map #(trace/trace-graph cut {:node % :direction :both}) (:dataflow/seeds graph))]
      (merge graph (apply trace/graph-union components)))
    (throw (ex-info "relationship:caller-view expects a rooted semantic graph" {}))))

(defn caller-view [graph]
  (if (capture/fragment? graph)
    (capture/update-scope graph :root caller-graph)
    (caller-graph graph)))

(defn inherit [graph source]
  (reduce (fn [result modifier]
            (case modifier
              :cell-values (with-values result)
              (throw (ex-info "Unknown graph modifier" {:modifier modifier}))))
          graph (:graph/modifiers source)))
