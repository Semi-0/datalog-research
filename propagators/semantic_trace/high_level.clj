(ns propagators.semantic-trace.high-level
  "Observation-only projection between explicitly selected component boundaries."
  (:require [clojure.set :as set]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.directions :as directions]
            [propagators.semantic-trace.graph-facts :as graph-facts]
            [propagators.semantic-trace.tracer-modifiers :as modifiers]))

(defn- resolve-boundary [nodes selector]
  (if (contains? nodes selector)
    selector
    (let [matches (for [[id label] nodes :when (= (str selector) (str label))] id)]
      (case (count matches)
        1 (first matches)
        0 (throw (ex-info "High-level boundary is absent" {:boundary selector}))
        (throw (ex-info "High-level boundary is ambiguous"
                        {:boundary selector :matches (set matches)}))))))

(defn- boundary-edges [outgoing boundaries source]
  (loop [pending (vec (get outgoing source)) seen #{} edges #{}]
    (if (empty? pending)
      edges
      (let [node (peek pending)
            remaining (pop pending)]
        (cond
          (contains? boundaries node)
          (recur remaining seen (conj edges [source node]))

          (contains? seen node)
          (recur remaining seen edges)

          :else
          (recur (into remaining (get outgoing node)) (conj seen node) edges))))))

(defn- select-node-maps [graph boundaries]
  (reduce (fn [result field]
            (if (map? (get result field))
              (update result field select-keys boundaries)
              result))
          ;; Visibility does not determine the frozen values needed by public slots.
          graph [:nodes :node-kinds :node-ui :expansions]))

(defn- project [graph selectors]
  (let [boundaries (set (map #(resolve-boundary (:nodes graph) %) selectors))
        outgoing (reduce (fn [result [source target]]
                           (update result source (fnil conj #{}) target))
                         {} (:edges graph))
        edges (into #{} (mapcat #(boundary-edges outgoing boundaries %)) boundaries)]
    (directions/annotate
     (-> (select-node-maps graph boundaries)
         ;; Keep the source's frozen values and reference aliases for inspection.
         (assoc :edges (vec (sort-by pr-str edges))
                :dataflow/representation :high-level
                :dataflow/seeds (set/intersection boundaries (set (:dataflow/seeds graph)))
                :dataflow/observer-applications
                (set/intersection boundaries (set (:dataflow/observer-applications graph)))
                :high-level/source graph
                :high-level/boundaries boundaries)
         (dissoc :bidirectional-edges)))))

(defn view
  "Keep named boundaries (or exact node IDs), connecting directed paths through
  hidden nodes up to the next boundary. Preserve values and captured facts.
  Names must resolve uniquely; no internal-node naming heuristic is applied."
  [graph selectors]
  (when-not (capture/semantic-graph? graph)
    (throw (ex-info "relationship:high-level expects a semantic graph" {})))
  (when-not (seq selectors)
    (throw (ex-info "High-level tracing requires explicit boundaries" {})))
  (modifiers/apply-modifiers graph
                             [(modifiers/declaration :high-level)
                              (modifiers/selection selectors)]))

(defn- caller-projection [graph]
  (when-not (seq (:dataflow/seeds graph))
    (throw (ex-info "Caller view requires rooted graph facts" {})))
  (let [excluded (:dataflow/observer-applications graph #{})
        cut (update graph :edges #(filterv (fn [[a b]] (not (or (excluded a) (excluded b)))) %))
        components (map #(trace/trace-graph cut {:node % :direction :both}) (:dataflow/seeds graph))]
    (merge graph (apply trace/graph-union components))))

(defn display-graph
  "Interpret declarations only at the display boundary. Source facts remain intact."
  [graph]
  (let [graph (if (graph-facts/enabled? graph :caller) (caller-projection graph) graph)
        selected (graph-facts/selected graph)
        selecting? (graph-facts/enabled? graph :select)
        projected (cond
                    (and selecting? (empty? selected))
                    (-> (select-node-maps graph #{}) (assoc :edges []))
                    (and selecting? (graph-facts/enabled? graph :high-level))
                    (project graph selected)
                    selecting?
                    (let [ids (set (map #(resolve-boundary (:nodes graph) %) selected))]
                      (-> (select-node-maps graph ids)
                          (update :edges #(filterv (fn [[a b]] (and (ids a) (ids b))) %))))
                    :else graph)]
    (cond-> projected
      (graph-facts/enabled? graph :with-values)
      (update :graph/modifiers (fnil conj #{}) :cell-values))))
