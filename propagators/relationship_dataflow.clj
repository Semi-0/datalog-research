(ns propagators.relationship-dataflow
  "Experimental Compiler 2 application projection, independent of rendering."
  (:require [propagators.cells.value :as value]
            [propagators.combinator :as combinator]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.gur.flat :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]
            [propagators.relationship-observer :as observer]
            [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.projection :as semantic]
            [propagators.semantic-trace.facts :as semantic-facts]
            [propagators.semantic-trace.logic :as logic]))

(defn- application-routing [network app]
  (semantic-facts/routing network app))

(defn port-edges [{:keys [id inputs outputs]}]
  (vec (distinct (concat (map #(vector % id) inputs)
                         (map #(vector id %) outputs)))))

(defn forward-edges [{:keys [arguments]}]
  (when (< (count arguments) 2)
    (throw (ex-info "Routing requires at least two cells" {:arguments arguments})))
  (mapv vec (partition 2 1 arguments)))

(defn bidirectional-edges [application]
  (let [forward (forward-edges application)]
    (into forward (map #(vec (reverse %)) forward))))

(defn reject-routing [application]
  (throw (ex-info "Unsupported graph routing"
                  {:routing (:routing application) :application-id (:id application)})))

(def application-edges
  (combinator/branch
   #(= :forward (:routing %)) forward-edges
   #(= :bidirectional (:routing %)) bidirectional-edges
   #(= :application (:routing %)) port-edges
   reject-routing))

(defn dataflow-graph
  "Project selected application roles from the current Net into a graph value.
  Unknown/non-application topology is outside this experimental projection.
  Bidirectional applications retain both edges; fan-out prevents contraction."
  [network operational]
  (when-not (trace/semantic-trace-graph? operational)
    (throw (ex-info "relationship:dataflow expects a relationship graph"
                    {:value operational})))
  (semantic/project network operational application-edges))

(defn semantic-network-graph [network operational]
  (when-not (trace/semantic-trace-graph? operational)
    (throw (ex-info "Semantic network expects a relationship graph" {:value operational})))
  (semantic/project-network network operational application-edges))

(defn child-semantic-network-graph [network parent]
  (when-not (prop/prop? (observer/node-entry network parent))
    (throw (ex-info "Expected a propagator occurrence" {:parent parent})))
  (semantic-network-graph network
                          (assoc (observer/snapshot network #{parent}) :dataflow/scope parent)))

(defn- body-return-aliases [network path application-id]
  (let [boundaries
        (for [[id {:keys [inputs outputs]}] (net/net-graph network)
              :let [entry (net/network-env-lookup network id)]
              :when (and (prop/prop? entry)
                         (= (application/boundary-name application-id :outbound :body-result)
                            (prop/prop-name entry))
                         (= 1 (count inputs)) (= 1 (count outputs)))]
          [(first outputs) (first inputs)])
        returns (set (map first boundaries))
        routes (for [app (application/application-topologies network)
                     :when (= :forward (application-routing network app))
                     :let [arguments (:argument-ids app)]
                     :when (and (= 2 (count arguments))
                                (contains? returns (last arguments)))]
                 [(last arguments) (first arguments)])]
    (into {}
          (keep (fn [[target source]]
                  (when (not= target source) [[path target] [path source]])))
          (concat boundaries routes))))

(defn copy-predecessors
  "Known directional copies: -> arguments and this call's outbound boundary.
  Child-call plumbing is excluded; local parameters remain distinct sources."
  [network application-id]
  (let [forward (mapcat (fn [app]
                          (when (= :forward (application-routing network app))
                            (mapv vec (partition 2 1 (:argument-ids app)))))
                        (application/application-topologies network))
        outbound (for [[id {:keys [inputs outputs]}] (net/net-graph network)
                       :let [entry (net/network-env-lookup network id)
                             name (when (prop/prop? entry) (prop/prop-name entry))]
                       :when (and (vector? name) (= 4 (count name))
                                  (= :compiler-2/application (first name)) (= application-id (second name))
                                  (= :outbound (nth name 2))
                                  (= 1 (count inputs)) (= 1 (count outputs)))]
                   [(first inputs) (first outputs)])]
    (reduce (fn [index [source target]]
              (if (= source target) index (update index target (fnil conj #{}) source)))
            {} (concat forward outbound))))

(defn return-flow
  "Follow actual copy routes backwards from a return, retaining every identity."
  [predecessors result-id]
  (let [derived (logic/run {:return #{[result-id]}
                           :predecessor (set (for [[target sources] predecessors source sources] [target source]))}
                          logic/return-rules)
        reachable (set (map first (:return-reachable derived)))]
    {:sources (set (remove #(seq (get predecessors %)) reachable))
     :edges (vec (:return-edge derived))}))

(defn- return-boundary-graph [root network path app]
  (let [result (:result-id app)
        flow (return-flow (copy-predecessors network (:application-id app)) result)
        routes (mapv (fn [[source target]] [[path source] [path target]]) (:edges flow))
        selected (conj (set (mapcat identity routes)) [path result])
        ports (disj (set (map second (:edges flow))) result)]
    (trace/graph-union
     (assoc (observer/snapshot root selected) :edges routes)
     {:nodes (assoc (into {} (map #(vector [path %] 'return-port)) ports) [path result] 'return)})))

(defn child-dataflow-graph
  "One occurrence's body. Traverse structural wrappers, stop at child calls.
  Selecting a recursive child repeats this operation without unfolding siblings."
  [network [path _ :as parent]]
  (when-not (prop/prop? (observer/node-entry network parent))
    (throw (ex-info "Expected a propagator occurrence" {:parent parent})))
  (let [owner (observer/network-at-path network path)
        topologies (application/application-topologies owner)
        parent-application (first (filter #(= parent [path (gur/stable-node-id [(:application-id %) :apply-prop])])
                                         topologies))
        aliases (if parent-application
                  (body-return-aliases owner path (:application-id parent-application))
                  {})]
    (let [projected (dataflow-graph network (assoc (observer/snapshot network #{parent}) :dataflow/aliases aliases :dataflow/scope parent))]
      (if (and parent-application (empty? (:nodes projected)))
        (assoc (return-boundary-graph network owner path parent-application)
               :dataflow/scope parent :dataflow/projection :leapfrog)
        projected))))

(defn project-result
  [project network graph input-id output-id]
  (try
    (project network graph)
    (catch InterruptedException error
      (.interrupt (Thread/currentThread))
      (throw error))
    (catch java.util.concurrent.CancellationException error
      (throw error))
    (catch Exception error
      (value/contradiction-with-provenance
       [{:operation :relationship/dataflow
         :input-cell input-id :output-id output-id
         :reason (or (ex-message error) (.getName (class error)))
         :exception-class (.getName (class error))}]))))

(defn p:dataflow [operational-id semantic-id]
  (prop/construct-propagator
   :relationship/dataflow
   (prop/concrete-propagator
    (fn [_inputs _outputs network]
      (let [operational (net/network-cell-strongest network operational-id)
            result (project-result dataflow-graph network operational
                                   operational-id semantic-id)]
        [(message semantic-id result)])))
   [operational-id] [semantic-id]))

(defn p:semantic-network [operational-id semantic-id]
  (prop/construct-propagator
   :relationship/semantic-network
   (prop/concrete-propagator
    (fn [_inputs _outputs network]
      (let [operational (net/network-cell-strongest network operational-id)
            result (project-result semantic-network-graph network operational operational-id semantic-id)]
        [(message semantic-id result)])))
   [operational-id] [semantic-id]))
