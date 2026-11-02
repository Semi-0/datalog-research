(ns propagators.semantic-trace.high-level
  "Supported view facts over an immutable observation. Display decides visibility."
  (:require [clojure.set :as set]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.directions :as directions]
            [propagators.semantic-trace.graph-facts :as facts]
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

(defn- fact-id [graph predicate tuple]
  (facts/assertion-id (facts/observation graph) (facts/scope graph) predicate tuple))

(defn- contribute [graph predicate tuple base contributors]
  (facts/derive graph {:predicate predicate :tuple tuple :base base
                       :contributors (set contributors)}))

(defn- derive-members [graph source]
  (reduce
   (fn [g declaration]
     (if (= :select (:kind declaration))
       (reduce (fn [g selector]
                 (let [node (resolve-boundary (:nodes source) selector)]
                   (contribute g :view/member [node] true
                               [(fact-id source :view/declaration [declaration])
                                (fact-id source :node/label [node (get (:nodes source) node)])])))
               g (:selection declaration))
       g))
   graph (facts/declarations source)))

(defn- derive-values [graph source]
  (if-not (facts/enabled? source :with-values)
    graph
    (let [declaration (fact-id source :view/declaration [(modifiers/declaration :with-values)])]
      (reduce
       (fn [g [field view-predicate source-predicate]]
         (reduce-kv (fn [g node captured]
                      (facts/capture-value g
                        {:predicate view-predicate :tuple [node] :base captured
                         :contributors #{declaration (fact-id source source-predicate [node])}}))
                    g (get source field {})))
       graph [[:values :view/value :node/value] [:contents :view/content :node/content]]))))

(defn- derive-paths
  "Candidate paths have positive evidence only. Recording interior nodes lets
  display hide paths crossing another selected boundary, without retracting a
  true path fact when selection grows. Simple paths avoid circular self-support."
  [graph source boundaries budget]
  (let [high-level (fact-id source :view/declaration [(modifiers/declaration :high-level)])
        edge-id #(fact-id source :graph/edge %)
        outgoing (group-by first (:edges source))
        direct (reduce (fn [g [a b :as edge]]
                         (contribute g :view/path [a b #{}] true [high-level (edge-id edge)]))
                       graph (:edges source))]
    (loop [pending (mapv (fn [start] {:start start :node start :visited #{start} :edges #{}})
                        (sort-by pr-str boundaries))
           result direct steps 0]
      (if (empty? pending)
        result
        (do
          (when (>= steps budget)
            (throw (ex-info "High-level path budget exhausted"
                            {:budget budget :observation (facts/observation source)})))
          (let [{:keys [start node visited edges]} (peek pending)
                [result next]
                (reduce
                 (fn [[g next] [_ target :as edge]]
                   (if (and (visited target) (not= target start))
                     [g next]
                     (let [proof (conj edges (edge-id edge))
                           g (if (and (boundaries target) (> (count proof) 1))
                               (contribute g :view/path [start target (disj visited start target)] true
                                           (into proof [high-level
                                                        (fact-id source :view/member [start])
                                                        (fact-id source :view/member [target])]))
                               g)]
                       [g (if (visited target) next
                              (conj next {:start start :node target
                                          :visited (conj visited target) :edges proof}))])))
                 [result (pop pending)] (get outgoing node))]
            (recur next result (inc steps))))))))

(defn- derive-scope [graph budget]
  (let [source (facts/current-graph graph)
        members (derive-members graph source)
        valued (derive-values members source)
        boundaries (set (map #(resolve-boundary (:nodes source) %) (facts/selected source)))]
    (if (facts/enabled? source :high-level)
      (derive-paths valued source boundaries budget)
      valued)))

(defn derive-view
  "Add supported relationships and captured-value facts; retain every source fact.
  View construction is bounded and never reads a live network."
  ([graph] (derive-view graph 10000))
  ([graph budget]
   (when-not (pos-int? budget) (throw (ex-info "Positive view budget required" {})))
   (if (capture/fragment? graph)
     (capture/update-scope graph :root #(derive-scope % budget))
     (derive-scope (if (facts/fact-graph? graph) graph
                      (facts/from-graph ::legacy :root graph #{})) budget))))

(defn view
  "Declare explicit boundaries for a legacy high-level call. Rendering is separate."
  [graph selectors]
  (when-not (capture/semantic-graph? graph)
    (throw (ex-info "relationship:high-level expects a semantic graph" {})))
  (when-not (seq selectors)
    (throw (ex-info "High-level tracing requires explicit boundaries" {})))
  (derive-view (modifiers/apply-modifiers graph
                 [(modifiers/declaration :high-level) (modifiers/selection selectors)])))

(defn- select-node-maps [graph boundaries]
  (reduce (fn [result field]
            (if (map? (get result field))
              (update result field select-keys boundaries)
              result))
          graph [:nodes :node-kinds :node-ui :expansions]))

(defn- project [graph boundaries]
  (let [paths (get-in graph [:graph/facts :view/path] #{})
        edges (into #{} (keep (fn [[a b interior]]
                               (when (and (boundaries a) (boundaries b)
                                          (empty? (set/intersection interior boundaries)))
                                 [a b]))) paths)]
    (directions/annotate
     (-> (select-node-maps graph boundaries)
         (assoc :edges (vec (sort-by pr-str edges))
                :dataflow/representation :high-level
                :dataflow/seeds (set/intersection boundaries (set (:dataflow/seeds graph)))
                :dataflow/observer-applications
                (set/intersection boundaries (set (:dataflow/observer-applications graph)))
                :high-level/source graph :high-level/boundaries boundaries)
         (dissoc :bidirectional-edges)))))

(defn caller-projection
  "Pure caller-plane selection, also used before acquisition has an observation ID."
  [graph]
  (when-not (seq (:dataflow/seeds graph))
    (throw (ex-info "Caller view requires rooted graph facts" {})))
  (let [excluded (:dataflow/observer-applications graph #{})
        cut (update graph :edges #(filterv (fn [[a b]] (not (or (excluded a) (excluded b)))) %))
        components (map #(trace/trace-graph cut {:node % :direction :both}) (:dataflow/seeds graph))]
    (merge graph (apply trace/graph-union components))))

(defn display-graph
  "Interpret current facts and declarations at the display boundary."
  [scope]
  (let [scope (capture/root-graph scope)
        current (facts/current-graph scope)
        graph (if (facts/enabled? scope :caller) (caller-projection current) current)
        selected (facts/selected scope)
        selecting? (facts/enabled? scope :select)
        boundaries (when selecting? (set (map #(resolve-boundary (:nodes graph) %) selected)))
        projected (cond
                    (and selecting? (empty? selected))
                    (-> (select-node-maps graph #{}) (assoc :edges []))
                    (and selecting? (facts/enabled? scope :high-level))
                    (project graph boundaries)
                    selecting?
                    (-> (select-node-maps graph boundaries)
                        (update :edges #(filterv (fn [[a b]] (and (boundaries a) (boundaries b))) %)))
                    :else graph)]
    (cond-> projected
      (facts/enabled? scope :with-values)
      (update :graph/modifiers (fnil conj #{}) :cell-values))))
