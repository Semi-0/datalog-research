(ns propagators.compiler-2.runtime.operators.network-traversal
  "Semantic graph snapshots implement the same read-only traversal API as raw snapshots."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.compiler-2.runtime.operators.network-sampling :as sampling]
            [propagators.compiler-2.runtime.operators.retained-observation :as retained]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.propagator :as prop]
            [propagators.stdlib.supported-procedure :as supported]
            [propagators.ids :as ids]
            [propagators.semantic-trace :as trace]))

(defn- node-key [graph node]
  (let [key (if (ids/node-id? node) [[:outer] node] node)
        aliases (get (:node-aliases graph) key)]
    (cond
      (contains? (:nodes graph) key) key
      (= 1 (count aliases)) (first aliases)
      :else (throw (ex-info "Node is absent or ambiguous in the semantic snapshot" {:node node})))))

(defrecord SemanticGraphSnapshot [graph]
  observation/NetworkSnapshot
  (snapshot-neighbors [_ node]
    (let [id (node-key graph node)]
      {:node/id id
       :node/inputs (vec (sort-by pr-str (for [[source target] (:edges graph) :when (= id target)] source)))
       :node/outputs (vec (sort-by pr-str (for [[source target] (:edges graph) :when (= id source)] target)))}))
  (snapshot-node-info [_ node]
    (let [id (node-key graph node)]
      {:node/id id :node/kind (get (:node-kinds graph) id)
       :node/name (get (:nodes graph) id)}))
  observation/SemanticSnapshot
  (snapshot-semantic-graph [_ root]
    (node-key graph root)
    graph))

(defn semantic-snapshot [snapshot root]
  (if (satisfies? observation/SemanticSnapshot snapshot)
    (->SemanticGraphSnapshot (observation/snapshot-semantic-graph snapshot root))
    (throw (ex-info "semantic-snapshot expects a frozen network snapshot" {}))))

(defn traverse [snapshot root direction]
  (when-not (satisfies? observation/SemanticSnapshot snapshot)
    (throw (ex-info "traverse expects a semantic snapshot" {})))
  (when-not (contains? #{:upstream :downstream :both} direction)
    (throw (ex-info "Traversal direction must be :upstream, :downstream, or :both" {:direction direction})))
  (let [graph (observation/snapshot-semantic-graph snapshot root)
        reached (trace/trace-graph graph {:node (node-key graph root) :direction direction})
        calls (set (filter #(= :propagator (get (:node-kinds graph) %)) (keys (:nodes reached))))
        interfaces (filter (fn [[source target]] (or (calls source) (calls target))) (:edges graph))
        nodes (into (set (keys (:nodes reached))) (mapcat identity interfaces))
        edges (filterv (fn [[source target]] (and (nodes source) (nodes target))) (:edges graph))
        selected (trace/graph-union
                  {:nodes (select-keys (:nodes graph) nodes)
                   :node-kinds (select-keys (:node-kinds graph) nodes)
                   :node-aliases (into {} (filter (fn [[_ aliases]] (some nodes aliases))) (:node-aliases graph))
                   :values (select-keys (:values graph) nodes)
                   :node-ui (select-keys (:node-ui graph) nodes)
                   :expansions (select-keys (:expansions graph) nodes)
                   :edges edges
                   :bidirectional-edges (set (filter (set edges) (:bidirectional-edges graph)))})]
    (assoc (merge graph selected) :traversal/direction direction)))

(defn- value-operator [name arity compute]
  (let [base (supported/from-primitive
               (prop/primitive-propagator
                 name
                 (fn [& values]
                   (cond
                     (some value/contradiction? values)
                     (value/contradiction-with-provenance
                       (mapcat value/contradiction-provenance
                               (filter value/contradiction? values)))
                     (some value/nothing? values) value/nothing
                     :else (apply compute values)))))]
    (operator/operator-closure
      {:name name
       :application-installer
       (fn [network arguments output _context]
         (when-not (= arity (count arguments))
           (throw (ex-info (str name " received the wrong number of inputs") {:expected arity})))
         (let [[installed props selected] (supported/install base network arguments output)]
           [(declarations/register-props installed props) props selected]))})))

(defn traversal-environment []
  (into (observation/observation-environment)
        [['semantic-snapshot (value-operator 'semantic-snapshot 2 semantic-snapshot)]
         ['traverse (value-operator 'traverse 3 traverse)]
         ['observe-network (sampling/observe-operator)]
         ['observe-network-retained (retained/operator)]]))
