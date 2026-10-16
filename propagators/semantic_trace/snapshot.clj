(ns propagators.semantic-trace.snapshot
  "Semantic projection of a frozen observation subject; never installs topology."
  (:require [propagators.ids :as ids]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.relationship-observer :as observer]
            [propagators.semantic-trace.facts :as facts]
            [propagators.semantic-trace.modifiers :as modifiers]))

(defn root-graph [network root {:keys [output]}]
  (when-not (ids/node-id? root)
    (throw (ex-info "Semantic snapshot expects a cell identity" {:root root})))
  (when-not (observer/node-entry network [[:outer] root])
    (throw (ex-info "Semantic snapshot root does not exist" {:root root})))
  (let [selected ((observer/connected-root-node-keys [root] (set [output])) network)
        seeds (set (map #(vector [:outer] %) (facts/root-seeds network [root])))
        operational (assoc (observer/snapshot network selected) :dataflow/seeds seeds)
        operational (if output
                      (assoc operational :dataflow/observer-outputs #{[[:outer] output]})
                      operational)
        graph (dataflow/semantic-network-graph network operational)
        graph (if output (modifiers/caller-view graph) graph)
        root-key [[:outer] root]
        graph (if (contains? (:nodes graph) root-key)
                graph
                (assoc-in graph [:node-aliases root-key] (:dataflow/seeds graph)))]
    (dissoc graph :dataflow/observer-outputs :dataflow/observer-applications)))
