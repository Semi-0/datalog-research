(ns propagators.relationship-dataflow
  "Experimental Compiler 2 application projection, independent of rendering."
  (:require [propagators.cells.value :as value]
            [propagators.combinator :as combinator]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.application-ports :as ports]
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
             :when (contains? (:nodes operational) id)
             :let [interface (ports/application-ports owner app)]
             :when (not (value/nothing? interface))]
         {:id id :routing (:routing interface)
          :label (get (:nodes operational) id)
          :inputs (mapv #(vector path %) (:inputs interface))
          :outputs (mapv #(vector path %) (:outputs interface))
          :arguments (mapv #(vector path %) (:argument-ids app))
          :result [path (:result-id app)]})))
   (distinct (map first (keys (:nodes operational))))))

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

(defn- retain-consumed-results [applications]
  (let [consumers (reduce (fn [index {:keys [id inputs]}]
                            (reduce #(update %1 %2 (fnil conj #{}) id) index inputs))
                          {} applications)]
    (mapv (fn [{:keys [id result outputs routing] :as app}]
            (if (and (= :application routing)
                     (seq (disj (get consumers result #{}) id))
                     (not (some #{result} outputs)))
              (update app :outputs conj result)
              app))
          applications)))

(defn- contractions [operational applications edges]
  (let [outgoing (group-by first edges)
        results (set (map :result (filter #(= :application (:routing %))
                                         applications)))]
    (into {}
          (keep (fn [{:keys [routing arguments]}]
                  (let [[source target] arguments]
                    (when (and (= :forward routing)
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
  (let [applications (retain-consumed-results
                      (remove #(contains? #{'xr:io 'io:xr} (:label %))
                              (selected-applications network operational)))
        edges (vec (distinct (mapcat application-edges applications)))
        aliases (contractions operational applications edges)
        canonical #(get aliases % %)
        edges (vec (distinct (keep (fn [[from to]]
                                    (let [a (canonical from) b (canonical to)]
                                      (when (or (not= a b) (= from to)) [a b])))
                                  edges)))
        selected (set (mapcat identity edges))
        operations (into {} (map (juxt :id :label))
                         (filter #(= :application (:routing %)) applications))
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
