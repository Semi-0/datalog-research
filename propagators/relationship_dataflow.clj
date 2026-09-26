(ns propagators.relationship-dataflow
  "Experimental Compiler 2 application projection, independent of rendering."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.gur.flat :as gur]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship-observer :as observer]
            [propagators.semantic-trace :as trace]))

(defn- named-label? [label]
  (and (some? label)
       (not (ids/node-id? label))
       (not (contains? #{:cell/anonymous "cell"} label))))

(defn- selected-applications [network operational]
  (mapcat
   (fn [path]
     (let [owner (observer/network-at-path network path)]
       (for [app (application/application-topologies owner)
             :let [id [path (gur/stable-node-id [(:application-id app) :apply-prop])]]
             :when (contains? (:nodes operational) id)]
         {:id id
          :label (get (:nodes operational) id)
          :arguments (mapv #(vector path %) (:argument-ids app))
          :result [path (:result-id app)]})))
   (distinct (map first (keys (:nodes operational))))))

(defn- application-edges [{:keys [id label arguments result]}]
  (cond
    (= '-> label)
    (if (= 2 (count arguments))
      [[(first arguments) (second arguments)]]
      (throw (ex-info "Invalid routing application" {:id id})))

    (= '<-> label)
    (if (= 2 (count arguments))
      [arguments (vec (reverse arguments))]
      (throw (ex-info "Invalid bidirectional application" {:id id})))

    :else
    (conj (mapv #(vector % id) arguments) [id result])))

(defn- contractions [operational applications edges]
  (let [outgoing (group-by first edges)
        results (set (map :result (remove #(contains? #{'-> '<->} (:label %))
                                         applications)))]
    (into {}
          (keep (fn [{:keys [label arguments]}]
                  (let [[source target] arguments]
                    (when (and (= '-> label)
                               (contains? results source)
                               (not (named-label? (get (:nodes operational) source)))
                               (named-label? (get (:nodes operational) target))
                               (= 1 (count (get outgoing source))))
                      [source target]))))
          applications)))

(defn dataflow-graph
  "Project selected application roles from the current Net into a graph value.
  Unknown/non-application topology is outside this experimental projection.
  Bidirectional applications retain both edges; fan-out prevents contraction."
  [network operational]
  (when-not (trace/semantic-trace-graph? operational)
    (throw (ex-info "relationship:dataflow expects a relationship graph"
                    {:value operational})))
  (let [applications (remove #(contains? #{'xr:io 'io:xr} (:label %))
                             (selected-applications network operational))
        edges (vec (distinct (mapcat application-edges applications)))
        aliases (contractions operational applications edges)
        canonical #(get aliases % %)
        edges (vec (distinct (keep (fn [[from to]]
                                    (let [a (canonical from) b (canonical to)]
                                      (when (or (not= a b) (= from to)) [a b])))
                                  edges)))
        selected (set (mapcat identity edges))
        operations (into {} (map (juxt :id :label))
                         (remove #(contains? #{'-> '<->} (:label %)) applications))
        cells (remove #(contains? operations %) selected)
        sampled (observer/snapshot network cells)
        labels (into {}
                     (map (fn [id]
                            (let [label (get (:nodes sampled) id)
                                  v (get (:values sampled) id)]
                              [id (cond
                                    (named-label? label) label
                                    (or (number? v) (string? v) (boolean? v)) (pr-str v)
                                    :else "cell")]))
                          cells))]
    (trace/graph-union
     {:nodes (merge labels (select-keys operations selected))
      :node-kinds (into {} (map (fn [id]
                                  [id (if (contains? operations id) :propagator :cell)]))
                        selected)
      :values (select-keys (:values sampled) cells)
      :edges edges})))

(defn p:dataflow [operational-id semantic-id]
  (prop/construct-propagator
   :relationship/dataflow
   (fn [_inputs _outputs network]
     (let [operational (net/network-cell-strongest network operational-id)]
       (if (value/unusable? operational)
         []
         [(message semantic-id (dataflow-graph network operational))])))
   [operational-id] [semantic-id]))
