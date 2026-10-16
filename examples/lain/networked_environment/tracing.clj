(ns examples.lain.networked-environment.tracing
  "Pure queries over received Nets. Import never installs executable topology."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.application-ports :as ports]
            [propagators.graph :as graph]
            [propagators.network :as net]
            [propagators.relationship :as relationship]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.relationship-observer :as observer]
            [propagators.semantic-trace :as trace]))

(defn environment? [x]
  (and (map? x) (true? (:peer/environment x))))

(defn reference [context binding-name]
  (when-not (and (environment? context) (net/net? (:peer/net context))
                 (string? binding-name))
    (throw (ex-info "ref expects a received environment and binding name" {})))
  (if-let [id (env/resolve-binding-id (:peer/net context) (:peer/env context)
                                     (symbol binding-name))]
    {:peer/reference true :context context :path [:outer] :node id}
    value/nothing))

(defn semantic-graph [reference]
  (let [network (get-in reference [:context :peer/net])
        path (:path reference)
        owner (observer/network-at-path network path)
        id (:node reference)
        selected ((observer/connected-root-node-keys [id]) owner)
        sampled (observer/snapshot owner selected)]
    (when-not owner
      (throw (ex-info "Reference has no environment context" {:path path})))
    (dataflow/dataflow-graph owner sampled)))

(defn trace-reference [reference direction]
  (when-not (true? (:peer/reference reference))
    (throw (ex-info "Trace requires a cell or propagator reference" {})))
  (trace/trace-graph (semantic-graph reference)
                     {:node [[:outer] (:node reference)] :direction direction}))

(defn interfaces [reference direction]
  (let [network (get-in reference [:context :peer/net])
        owner (observer/network-at-path network (:path reference))
        id (:node reference)
        semantic (semantic-graph reference)
        key [[:outer] id]
        edges (:edges semantic)
        neighbors (case direction
                    :inputs (keep (fn [[a b]] (when (= key b) a)) edges)
                    :outputs (keep (fn [[a b]] (when (= key a) b)) edges)
                    (throw (ex-info "Unknown interface direction" {:direction direction})))
        ;; A standalone explicit propagator can have no projected applications.
        adjacent (if (contains? (:nodes semantic) key)
                   neighbors
                   (let [node (graph/get-node (net/net-graph owner) id)]
                     (map #(vector [:outer] %)
                          (case direction
                            :inputs (graph/node-input-ids node)
                            :outputs (graph/node-output-ids node)))))]
    (set (map (fn [[path node]] (assoc reference :path path :node node)) adjacent))))

(defn child-graph [reference]
  (let [network (get-in reference [:context :peer/net])
        key (relationship/node-key (:path reference) (:node reference))]
    (dataflow/child-dataflow-graph network key)))
