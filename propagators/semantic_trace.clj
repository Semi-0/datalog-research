(ns propagators.semantic-trace
  "Semantic graph tracing as data and as a propagator."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn- alias-node-set
  [v]
  (cond
    (nil? v) #{}
    (set? v) v
    (sequential? v) (set v)
    :else #{v}))

(defn- merge-node-aliases
  [graphs]
  (apply merge-with
         set/union
         (map (fn [aliases]
                (into {}
                      (map (fn [[k v]] [k (alias-node-set v)]))
                      aliases))
              (map #(or (:node-aliases %) {}) graphs))))

(defn- target-nodes
  [{:keys [nodes node-aliases]} {:keys [node label]}]
  (cond
    node (into #{node} (alias-node-set (get node-aliases node)))
    label (set (keep (fn [[id node-label]]
                       (when (= label node-label) id))
                     nodes))
    :else #{}))

(defn- alias-equivalents
  [node-aliases]
  (reduce (fn [equiv nodes]
            (reduce (fn [equiv node]
                      (update equiv node (fnil into #{}) nodes))
                    equiv
                    nodes))
          {}
          (map alias-node-set (vals node-aliases))))

(defn- expand-equivalents
  [equiv nodes]
  (into nodes (mapcat #(get equiv % #{})) nodes))

(defn- step-edges
  [edges direction frontier]
  (case direction
    :downstream (filter (fn [[from _to]] (contains? frontier from)) edges)
    :upstream (filter (fn [[_from to]] (contains? frontier to)) edges)
    (filter (fn [[from to]]
              (or (contains? frontier from)
                  (contains? frontier to)))
            edges)))

(defn semantic-trace-graph?
  [x]
  (and (map? x)
       (true? (:semantic-trace/graph x))))

(defn epoch
  [n]
  {:semantic-trace/epoch n})

(defn epoch?
  [x]
  (and (map? x)
       (contains? x :semantic-trace/epoch)))

(defn merge-epoch
  [& epochs]
  (epoch (apply max 0 (keep :semantic-trace/epoch epochs))))

(defn graph-union
  [& graphs]
  (let [graphs (remove value/unusable? graphs)]
    {:semantic-trace/graph true
     :nodes (apply merge (map :nodes graphs))
     :node-aliases (merge-node-aliases graphs)
     :values (apply merge (map :values graphs))
     :edges (vec (distinct (mapcat :edges graphs)))}))

(defn trace-graph
  "Return the semantic subgraph reachable from `:node` or `:label`.

  Direction defaults to `:upstream`; `:downstream` follows outgoing edges, and
  `:both` follows either side."
  [graph request]
  (let [edges (:edges graph)
        direction (or (:direction request) :upstream)
        equiv (alias-equivalents (:node-aliases graph))
        start (expand-equivalents equiv (target-nodes graph request))]
    (loop [seen start
           frontier start
           kept []]
      (if (empty? frontier)
        (let [kept (vec (distinct kept))]
          (graph-union {:nodes (select-keys (:nodes graph) seen)
                        :values (select-keys (:values graph) seen)
                        :edges kept}))
        (let [next-edges (vec (step-edges edges direction frontier))
              next-nodes (expand-equivalents equiv (set (mapcat identity next-edges)))
              new-nodes (set (remove seen next-nodes))]
          (recur (into seen new-nodes)
                 new-nodes
                 (into kept next-edges)))))))

(defn trace-request
  [source direction]
  (let [request (cond
                  (and (seq? source) (= 'cell (first source)))
                  (apply hash-map (rest source))

                  (map? source)
                  source

                  (symbol? source)
                  {:label (name source)}

                  (string? source)
                  {:label source}

                  :else
                  {})]
    (cond-> request
      (keyword? direction) (assoc :direction direction))))

(defn p:trace-request
  [source-id direction-id out-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [source (net/network-cell-strongest network source-id)
           direction (net/network-cell-strongest network direction-id)]
       (if (value/unusable? source)
         []
         [(message out-id (trace-request source direction))])))
   [source-id direction-id]
   [out-id]))

(defn p:cell-trace-request
  [source-id direction-id out-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [direction (net/network-cell-strongest network direction-id)]
       (if (value/unusable? direction)
         []
         [(message out-id {:node source-id :direction direction})])))
   [direction-id]
   [out-id]))

(defn p:fixed-trace-request
  [source-id direction out-id]
  (prop/construct-propagator
   (fn [_inputs _outputs _network]
     [(message out-id {:node source-id :direction direction})])
   []
   [out-id]))

(defn p:semantic-trace
  ([request-id graph-id out-id]
   (p:semantic-trace request-id graph-id nil out-id))
  ([request-id graph-id epoch-id out-id]
   (let [inputs (cond-> [request-id graph-id] epoch-id (conj epoch-id))]
     (prop/construct-propagator
      (fn [_inputs _outputs network]
        (let [request (net/network-cell-strongest network request-id)
              graph (net/network-cell-strongest network graph-id)]
          (if (or (value/unusable? request)
                  (value/unusable? graph))
            []
            [(message out-id (trace-graph graph request))])))
      inputs
      [out-id]))))
