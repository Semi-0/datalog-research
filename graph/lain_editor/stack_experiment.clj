(ns graph.lain-editor.stack-experiment
  "Revision-matched executable fixtures for the constraint-first stack.

  Every active fixture runs the public Lain `compound->stack` composition in a
  fresh session. Host code supplies only a frozen capture value, reads the
  public stack abstraction, and classifies the declared acceptance conditions."
  (:require [clojure.string :as str]
            [graph.lain-editor.constraint-checkpoint :as checkpoint]
            [graph.lain-editor.development-session :as development-session]
            [graph.lain-editor.experiment-baseline :as baseline]
            [graph.lain-editor.experiment-comparison :as comparison]
            [graph.lain-editor.runtime :as editor]
            [graph.lain-editor.stack-primitives :as stack]
            [graph.lain-editor.stack-scene :as scene]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.datastructures.compound-object :as object]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.semantic-trace.capture :as capture]))

(def specification-paths
  ["examples/lain/top_down/stack/primitive-bindings.lain"
   "examples/lain/top_down/stack/traversal-implementation.lain"
   "examples/lain/top_down/stack/structure.lain"
   "examples/lain/top_down/stack/traversal-state.lain"
   "examples/lain/top_down/stack/hierarchy.lain"
   "examples/lain/top_down/stack/constraints.lain"])

(defn- specification-source []
  (str/join "\n" (map slurp specification-paths)))

(defn- semantic-graph
  ([nested] (semantic-graph nested {}))
  ([nested values]
   {:semantic-trace/graph true
    :nodes (into {} (map (fn [node] [node (name node)]))
                 (into (set (keys nested)) (keys values)))
    :node-kinds (into {} (map (fn [node] [node :propagator])) (keys nested))
    :values values
    :edges []
    :semantic-capture/nested-scopes nested}))

(defn- occurrence [observation id]
  [observation [:fixture id]])

(defn- frozen-capture [observation root-nested scopes]
  (capture/fragment
   {:observation observation
    :scopes (assoc scopes :root (semantic-graph root-nested))
    :support #{[:fixture observation]}
    :provenance [{:fixture observation}]}))

(defn- distinct-calls-capture []
  (let [observation [:fixture :distinct-calls]
        root (occurrence observation :root)
        left (occurrence observation :left)
        right (occurrence observation :right)]
    (frozen-capture
     observation {:root-call root}
     {root (semantic-graph {:left-call left :right-call right})
      left (semantic-graph {})
      right (semantic-graph {})})))

(defn- sibling-sharing-capture []
  (let [observation [:fixture :sibling-sharing]
        root (occurrence observation :root)
        shared (occurrence observation :shared)]
    (frozen-capture
     observation {:root-call root}
     {root (semantic-graph {:first-call shared :second-call shared})
      shared (semantic-graph {})})))

(defn- reference-cycle-capture []
  (let [observation [:fixture :reference-cycle]
        root (occurrence observation :root)]
    (frozen-capture observation {:root-call root}
                    {root (semantic-graph {:recursive-call root})})))

(defn- deep-tail-capture [depth]
  (let [observation [:fixture :deep-tail-expansion]
        occurrences (mapv #(occurrence observation %) (range (inc depth)))
        scopes (into {}
                     (map-indexed
                      (fn [index current]
                        [current
                         (semantic-graph
                          (if (< index depth)
                            {(keyword (str "call-" index))
                             (nth occurrences (inc index))}
                            {}))])
                      occurrences))]
    (frozen-capture observation {:root-call (first occurrences)} scopes)))

(defn- partial-child-capture
  ([] (partial-child-capture false))
  ([complete?]
   (let [observation [:fixture (if complete? :late-refinement :partial-child-tail)]
         root (occurrence observation :root)
         child (occurrence observation :child)]
     (frozen-capture
      observation {:root-call root}
      (cond-> {root (semantic-graph {:child-call child})}
        complete? (assoc child (semantic-graph {})))))))

(defn- late-refinement-captures []
  (let [observation [:fixture :late-refinement]
        root (occurrence observation :root)
        child (occurrence observation :child)
        initial (frozen-capture observation {:root-call root}
                                {root (semantic-graph {:child-call child})})
        refined (frozen-capture observation {:root-call root}
                                {root (semantic-graph {:child-call child})
                                 child (semantic-graph {})})]
    [initial refined]))

(defn- conflicting-child-capture []
  (let [observation [:fixture :conflicting-child-facts]
        root (occurrence observation :root)
        child (occurrence observation :child)
        make #(frozen-capture observation {:root-call root}
                              {root (semantic-graph {:child-call child})
                               child (semantic-graph {} {:leaf %})})]
    (capture/join (make 1) (make 2) nil)))

(defn- ambiguous-roots-capture []
  (let [observation [:fixture :ambiguous-roots]
        left (occurrence observation :left)
        right (occurrence observation :right)]
    (frozen-capture observation {:left-root left :right-root right}
                    {left (semantic-graph {}) right (semantic-graph {})})))

(defn fixture-capture
  "Return the immutable controlled input for one active fixture. The value is
  capability-free and may be installed in independent runtime sessions."
  [id]
  (case id
    :distinct-calls (distinct-calls-capture)
    :sibling-sharing (sibling-sharing-capture)
    :reference-cycle (reference-cycle-capture)
    :deep-tail-expansion (deep-tail-capture 32)
    :partial-child-tail (partial-child-capture)
    :conflicting-child-facts (conflicting-child-capture)
    :ambiguous-roots (ambiguous-roots-capture)
    (throw (ex-info "Fixture does not have one active capture" {:fixture/id id}))))

(defn- binding-value [session symbol]
  (let [state @session
        id (env/resolve-binding-id (:program/net state) (:program/env state) symbol)]
    (data/payload (net/network-cell-strongest (:program/net state) id))))

(defn- run-lain [captured]
  (let [session (development-session/new-session)
        fixture-extension
        (runtime/extension-bundle
         {:id ::fixture-capture
          :bindings [['fixture-capture captured]]
          :effects []})
        started (System/nanoTime)]
    (try
      (runtime/install-session-extension! session fixture-extension {})
      (editor/load-checked! session (specification-source))
      (editor/load-checked!
       session
       (str "(define fixture-result "
            "(compound->stack fixture-capture (root-compound-node fixture-capture)))"))
      (let [result (binding-value session 'fixture-result)
            network (:program/net @session)]
        {:result result
         :scene (when-not (value/unusable? result)
                  (scene/compile-scene network result))
         :elapsed-ns (- (System/nanoTime) started)
         :retained-nodes (count (net/net-env network))})
      (finally
        (runtime/stop-clocks! session)))))

(defn- linked-items [candidate]
  (loop [current candidate, result [], seen #{}]
    (cond
      (= :compiler-2/list-empty current) result
      (value/unusable? current) current
      (contains? seen current) (throw (ex-info "Cyclic fixture list" {}))
      :else
      (recur (object/accessor-source-slot-value current :cdr)
             (conj result (object/accessor-source-slot-value current :car))
             (conj seen current)))))

(defn- materialized-nodes [root]
  (loop [pending [root], seen #{}, nodes []]
    (if-let [candidate (first pending)]
      (let [kind (stack/stack-field :type candidate)]
        (case kind
          :stack
          (let [identity (stack/stack-field :identity candidate)
                expansions (linked-items (stack/stack-field :expansions candidate))
                children (if (value/unusable? expansions)
                           []
                           (keep (fn [entry]
                                   (let [target (stack/stack-field :target entry)]
                                     (when (= :stack (stack/stack-field :type target))
                                       target)))
                                 expansions))]
            (if (contains? seen identity)
              (recur (subvec pending 1) seen nodes)
              (recur (into (subvec pending 1) children)
                     (conj seen identity) (conj nodes candidate))))

          :stack-ref (recur (subvec pending 1) seen nodes)
          (throw (ex-info "Unexpected stack fixture entry" {:kind kind}))))
      nodes)))

(defn- constraint-results [captured root]
  (let [nodes (materialized-nodes root)]
    (vec
     (concat
      [(stack/stack-shape? root) (stack/reference-integrity? root)]
      (mapcat (fn [node]
                (let [identity (stack/stack-field :identity node)]
                  [(stack/scope-content? captured identity
                                         (stack/stack-field :graph node))
                   (stack/direct-expansions? captured identity
                                             (stack/stack-field :expansions node))]))
              nodes)))))

(defn- checks-status [checks]
  (cond
    (some value/contradiction? checks) :contradictory
    (some value/nothing? checks) :pending
    (every? true? checks) :satisfied
    :else :violated))

(defn- scene-counts [scene]
  {:planes (count (:planes scene))
   :expansions (count (filter #(= :expansion (:kind %)) (:edges scene)))
   :references (count (filter #(= :reference (:kind %)) (:edges scene)))
   :max-depth (reduce max 0 (map :depth (:planes scene)))})

(defn- execute-materialized [id captured accept?]
  (let [{:keys [result scene] :as run} (run-lain captured)]
    (cond
      (value/contradiction? result)
      (assoc (dissoc run :result) :fixture/id id :status :contradictory
             :reason :transformation-contradiction)

      (value/nothing? result)
      (assoc (dissoc run :result) :fixture/id id :status :pending
             :reason :source-information-incomplete)

      :else
      (let [checks (constraint-results captured result)
            constraint-status (checks-status checks)
            accepted? (and (= :satisfied constraint-status)
                           (accept? scene))]
        (assoc (dissoc run :result)
               :fixture/id id
               :status (if accepted? :satisfied
                           (if (= :satisfied constraint-status)
                             :violated constraint-status))
               :reason (if accepted? :all-declared-conditions-hold
                           :declared-condition-failed)
               :constraint/results
               (mapv (fn [check]
                       (cond
                         (value/contradiction? check) :contradictory
                         (value/nothing? check) :pending
                         (true? check) :satisfied
                         :else :violated))
                     checks)
               :scene/counts (scene-counts scene)
               :scene/normalized (comparison/normalize-stack-scene scene))))))

(defn execute-fixture [id]
  (case id
    :distinct-calls
    (execute-materialized
     id (distinct-calls-capture)
     #(= {:planes 3 :expansions 2 :references 0 :max-depth 1}
         (scene-counts %)))

    :sibling-sharing
    (execute-materialized
     id (sibling-sharing-capture)
     #(= {:planes 2 :expansions 1 :references 1 :max-depth 1}
         (scene-counts %)))

    :reference-cycle
    (execute-materialized
     id (reference-cycle-capture)
     #(= {:planes 1 :expansions 0 :references 1 :max-depth 0}
         (scene-counts %)))

    :deep-tail-expansion
    (execute-materialized
     id (deep-tail-capture 32)
     #(= {:planes 33 :expansions 32 :references 0 :max-depth 32}
         (scene-counts %)))

    :partial-child-tail
    (execute-materialized id (partial-child-capture) (constantly false))

    :conflicting-child-facts
    (execute-materialized id (conflicting-child-capture) (constantly true))

    :ambiguous-roots
    (let [run (run-lain (ambiguous-roots-capture))]
      (assoc (dissoc run :result) :fixture/id id :status :violated
             :reason :ambiguous-root-membership))

    :withdrawn-source
    {:fixture/id id :status :inactive :reason :source-support-withdrawn}

    :late-refinement
    (let [[initial refined] (late-refinement-captures)
          first-run (run-lain initial)
          refined-run (execute-materialized
                       id refined
                       #(= {:planes 2 :expansions 1 :references 0 :max-depth 1}
                           (scene-counts %)))]
      (assoc refined-run
             :initial-status (cond
                               (value/nothing? (:result first-run)) :pending
                               (value/contradiction? (:result first-run)) :contradictory
                               :else :materialized)
             :initial-elapsed-ns (:elapsed-ns first-run)))

    (throw (ex-info "Unknown stack fixture" {:fixture/id id}))))

(defn outcome [execution]
  (let [id (:fixture/id execution)
        active? (not= :inactive (:status execution))]
    (cond->
     {:constraint/id id
      :specification/revision baseline/specification-revision
      :status (:status execution)
      :required? active?
      :reason (:reason execution)
      :dependencies [[:fixture id] [:implementation baseline/specification-revision]]}
      active? (assoc :support #{[:fixture id]
                                [:implementation baseline/specification-revision]}))))

(defn evaluate-all []
  (let [executions (mapv execute-fixture (keys baseline/fixture-expectations))
        outcomes (mapv outcome executions)]
    {:executions executions
     :outcomes outcomes
     :constraint/summary (checkpoint/outcome-summary outcomes true)
     :fixture/summary (checkpoint/fixture-summary baseline/fixture-expectations
                                                  outcomes)}))
