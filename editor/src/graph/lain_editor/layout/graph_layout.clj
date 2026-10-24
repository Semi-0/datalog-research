(ns graph.lain-editor.layout.graph-layout
  "Pane-owned Vijual Standard Stress flows. Only public session outputs are retained here."
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [graph.vijual.layout.nodes :as layout]
            [graph.vijual.layout.sugiyama :as sugiyama]
            [graph.lain-editor.layout.node-clearance :as clearance]
            [graph.lain-editor.workspace.model :as workspace]))

(defn- close-entry! [registry id]
  (when-let [entry (get @registry id)]
    (async/close! (:input entry))
    (async/close! (:output entry))
    (swap! registry dissoc id)))

(defn close-pane! [registry pane]
  (doseq [key (keys @registry)
          :when (or (= key pane) (and (vector? key) (= pane (first key))))]
    (close-entry! registry key)))

(defn close! [registry]
  (doseq [id (keys @registry)] (close-entry! registry id)))

(defn- create-entry [identity spacing nodes edges]
  (let [initial (layout/create-layout nodes edges {:stress-method :standard
                                           :gap (* 28 spacing) :edge-length (* 110 spacing) :tolerance 0.05
                                           ;; A larger layout footprint adds breathing room without
                                           ;; changing the public node or its rendered card dimensions.
                                           :size-fn (fn [node] (mapv #(* spacing %) (:size node)))})
        input (async/chan 1)]
    {:identity identity :spacing spacing :input input :output ((layout/layout-flow initial 1) input)
     :nodes nodes :edges (set edges)}))

(defn- normalize-request [{:keys [nodes edges moves] :as request}]
  (when (contains? request :animate)
    (workspace/ensure! (boolean? (:animate request)) "Layout animation flag must be boolean"))
  (when (contains? request :refine)
    (workspace/ensure! (boolean? (:refine request)) "Layout refinement flag must be boolean"))
  (when (contains? request :budgetMs)
    (workspace/ensure! (and (integer? (:budgetMs request)) (<= 1 (:budgetMs request) 10000))
                       "Layout time budget must be an integer from 1 to 10000 milliseconds"))
  (when (contains? request :spacing)
    (workspace/ensure! (and (number? (:spacing request)) (<= 1 (:spacing request) 4))
                       "Node spacing must be a number from 1 to 4"))
  (workspace/ensure! (and (vector? nodes) (every? #(string? (:id %)) nodes)) "Layout needs node IDs")
  (workspace/ensure! (every? (fn [{:keys [size]}]
                              (and (sequential? size) (= 2 (count size))
                                   (every? #(and (number? %) (Double/isFinite (double %)) (<= 0 %)) size)))
                            nodes)
                     "Layout sizes need two finite nonnegative numbers")
  (workspace/ensure! (= (count nodes) (count (set (map :id nodes)))) "Duplicate layout node")
  (let [ids (set (map :id nodes))]
    (workspace/ensure! (and (vector? edges)
                            (every? #(and (= 2 (count %)) (every? ids %)) edges)) "Invalid layout edge")
    (workspace/ensure! (every? #(and (ids (:id %)) (vector? (:position %))
                                    (= 2 (count (:position %)))
                                    (every? (fn [n] (and (number? n) (Double/isFinite (double n))))
                                            (:position %))) moves) "Invalid node move"))
  {:nodes nodes :directed-edges edges :edges (set (map #(vec (sort %)) edges)) :moves moves})

(defn- initial-positions [nodes edges]
  (let [ids (mapv :id nodes)
        layers (sugiyama/sugiyama-ordered-layers ids edges {})
        grid (sugiyama/sugiyama-grid layers)
        width (apply max 0 (map (comp first :size) nodes))
        height (apply max 0 (map (comp second :size) nodes))]
    (into {} (map (fn [[id [row column]]]
                    [id [(* (+ width 40) column) (* (+ height 48) row)]])) grid)))

(defn- changes [entry {:keys [nodes edges directed-edges moves]}]
  (let [old (into {} (map (juxt :id identity)) (:nodes entry))
        moved (into {} (map (juxt :id :position)) moves)
        ids (set (map :id nodes))
        seeds (if (some #(not (contains? old (:id %))) nodes)
                (initial-positions nodes directed-edges)
                {})
        fresh (mapv (fn [node]
                      (assoc node :position
                             (get moved (:id node)
                                  (get-in old [(:id node) :position] (get seeds (:id node)))))) nodes)]
    {:upsert-nodes (filterv #(not= % (get old (:id %))) fresh)
     :remove-node-ids (set/difference (set (keys old)) ids)
     :add-edges (vec (set/difference edges (:edges entry)))
     :remove-edges (filterv #(every? ids %) (set/difference (:edges entry) edges))}))

(defn- flow-step! [entry changes iterations]
  (workspace/ensure! (async/>!! (:input entry) {:changes changes :iterations iterations}) "Layout flow closed")
  (let [result (async/<!! (:output entry))]
    (workspace/ensure! result "Layout flow closed")
    (if-let [error (:error result)]
      (throw (ex-info (:message error) (:data error)))
      result)))

(defn- net-deltas [deltas]
  (let [weights (reduce (fn [m [node weight]] (update m node (fnil + 0) weight)) {} deltas)]
    (vec (remove #(zero? (second %)) weights))))

(defn- clear-result! [entry result]
  (let [separate #(clearance/separate % nil (* 28 (:spacing entry)))
        result (if (:frames result)
                 (update result :frames #(mapv (fn [frame] (update frame :nodes separate)) %))
                 result)
        nodes (separate (:nodes result))
        old (into {} (map (juxt :id identity)) (:nodes result))
        corrections (filterv #(not= % (get old (:id %))) nodes)]
    (if (seq corrections)
      (let [projected (flow-step! entry {:upsert-nodes corrections} 0)]
        (assoc result :nodes (:nodes projected) :revision (:revision projected)
                      :deltas (net-deltas (into (:deltas result) (:deltas projected)))
                      :metrics (assoc (:metrics result) :node-overlaps 0
                                      :exact-clearance-moves (count corrections))))
      (assoc result :metrics (assoc (:metrics result) :node-overlaps 0 :exact-clearance-moves 0)))))

(defn- sample-frame [frames frame]
  ;; Keep real milestones without retaining unbounded animation snapshots.
  (conj (if (>= (count frames) 64)
          (vec (take-nth 2 frames)) frames) frame))

(defn- solve! [entry changes animate? budget-ms now]
  (let [started (now)
        initial (flow-step! entry changes 0)
        finish (fn [result used frames deltas pairs reason]
                 (assoc result :frames frames :iterationsUsed used
                        :stopReason reason :converged (= reason "converged")
                        :budgetMs budget-ms :elapsedMs (/ (- (now) started) 1e6)
                        :deltas (net-deltas deltas)
                        :metrics (assoc (:metrics result) :iterations used :pair-evaluations pairs
                                        :distance-sources-recomputed
                                        (get-in initial [:metrics :distance-sources-recomputed]))))]
    (loop [result initial used 0
           frames (if animate? [{:iteration 0 :nodes (:nodes initial)}] [])
           deltas (:deltas initial) pairs 0]
      (if (>= (- (now) started) (* budget-ms 1000000))
        (finish result used frames deltas pairs "budget")
        (let [next (flow-step! entry {} 10)
              spent (get-in next [:metrics :iterations])
              used (+ used spent)
              frames (if animate? (sample-frame frames {:iteration used :nodes (:nodes next)}) [])
              deltas (into deltas (:deltas next))
              pairs (+ pairs (get-in next [:metrics :pair-evaluations] 0))]
          (if (or (< spent 10) (<= (get-in next [:metrics :max-displacement]) 0.05))
            (finish next used frames deltas pairs "converged")
            (recur next used frames deltas pairs)))))))

(defn- topology [nodes edges]
  {:nodes (set (map :id nodes)) :edges (set edges)})

(defn- node-sizes [nodes]
  (into {} (map (juxt :id :size)) nodes))

(defn- zero-work-result
  [entry {:keys [nodes moves]}]
  (let [old (into {} (map (juxt :id identity)) (:nodes entry))
        moved (into {} (map (juxt :id :position)) moves)
        result-nodes
        (mapv (fn [node]
                (assoc node :position
                       (get moved (:id node)
                            (get-in old [(:id node) :position]))))
              nodes)]
    {:nodes result-nodes
     :revision (inc (long (or (get-in entry [:result :revision]) 0)))
     :frames []
     :deltas []
     :metrics {:iterations 0
               :pair-evaluations 0
               :distance-sources-recomputed 0
               :max-displacement 0.0}}))

(defn compute!
  ([registry request] (compute! registry request #(System/nanoTime)))
  ([registry {:keys [pane generation parent semanticScope animate refine budgetMs spacing] :as request} now]
  (let [request-data (normalize-request request)
        identity [generation (or semanticScope parent :root)]
        key (if semanticScope [pane generation semanticScope] pane)
        previous (get @registry key)
        _ (when (and previous (not= identity (:identity previous))) (close-entry! registry key))
        spacing (or spacing 1)
        retained (get @registry key)
        spacing-changed? (and retained (not= spacing (:spacing retained)))
        entry (cond
                spacing-changed?
                (let [replacement (merge (create-entry identity spacing (:nodes retained) (:edges retained))
                                         (select-keys retained [:topology :result]))]
                  (close-entry! registry key)
                  replacement)
                retained retained
                :else (create-entry identity spacing [] []))]
    (swap! registry assoc key entry)
    (let [transaction (changes entry request-data)
          topology (topology (:nodes request-data) (:directed-edges request-data))
          topology-changed? (not= topology (:topology entry))
          sizes-changed? (not= (node-sizes (:nodes request-data)) (node-sizes (:nodes entry)))
          no-solve? (and (not topology-changed?)
                         (not sizes-changed?) (not spacing-changed?) (not (true? refine)))
          raw-result (cond
                       (and no-solve? (seq (:moves request-data)))
                       (assoc (flow-step! entry transaction 0) :stopReason "positioned" :iterationsUsed 0)
                       no-solve? (assoc (zero-work-result entry request-data) :stopReason "unchanged" :iterationsUsed 0)
                       :else (solve! entry transaction (true? animate) (or budgetMs 1000) now))
          previous-positions (into {} (map (juxt :id :position)) (:nodes entry))
          cleared (if no-solve? raw-result (clear-result! entry raw-result))
          cleared (if (and (:converged cleared) (pos? (get-in cleared [:metrics :exact-clearance-moves] 0)))
                    (assoc cleared :converged false :stopReason "clearance") cleared)
          cleared (assoc cleared :revision
                         (max (:revision cleared)
                              (inc (long (get-in entry [:result :revision] 0)))))
          result (cond-> (assoc cleared :spacing spacing)
                   spacing-changed?
                   (update-in [:metrics :distance-sources-recomputed] + (count (:nodes retained))))
          result (update result :metrics assoc :changed-positions
                         (count (filter #(not= (:position %) (get previous-positions (:id %)))
                                        (:nodes cleared))))]
      (swap! registry assoc key (assoc entry :nodes (:nodes result) :edges (:edges request-data)
                                       :topology topology :result result))
      result))))
