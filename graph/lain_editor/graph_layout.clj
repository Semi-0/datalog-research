(ns graph.lain-editor.graph-layout
  "Pane-owned Vijual Balanced Stress flows. Only public session outputs are retained here."
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [graph.vijual.layout.nodes :as layout]
            [graph.vijual.layout.sugiyama :as sugiyama]
            [graph.lain-editor.workspace :as workspace]))

(defn close-pane! [registry id]
  (when-let [entry (get @registry id)]
    (async/close! (:input entry))
    (async/close! (:output entry))
    (swap! registry dissoc id)))

(defn close! [registry]
  (doseq [id (keys @registry)] (close-pane! registry id)))

(defn- create-entry [identity]
  (let [initial (layout/create-balanced-layout [] [] {:gap 28 :edge-length 110 :tolerance 0.05
                                                    :edge-clearance 16 :edge-overlap-weight 96})
        input (async/chan 1)]
    {:identity identity :input input :output ((layout/layout-flow initial 1) input)
     :nodes [] :edges #{}}))

(defn- normalize-request [{:keys [nodes edges moves] :as request}]
  (when (contains? request :animate)
    (workspace/ensure! (boolean? (:animate request)) "Layout animation flag must be boolean"))
  (workspace/ensure! (and (vector? nodes) (every? #(string? (:id %)) nodes)) "Layout needs node IDs")
  (workspace/ensure! (= (count nodes) (count (set (map :id nodes)))) "Duplicate layout node")
  (let [ids (set (map :id nodes))]
    (workspace/ensure! (and (vector? edges)
                            (every? #(and (= 2 (count %)) (every? ids %)) edges)) "Invalid layout edge")
    (workspace/ensure! (every? #(and (ids (:id %)) (vector? (:position %))
                                    (= 2 (count (:position %)))) moves) "Invalid node move"))
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
     :remove-edges (filterv #(every? ids %) (set/difference (:edges entry) edges))
     :pin-ids (set (keys moved))}))

(defn- step! [entry changes iterations]
  (workspace/ensure! (async/>!! (:input entry) {:changes changes :iterations iterations}) "Layout flow closed")
  (let [result (async/<!! (:output entry))]
    (workspace/ensure! result "Layout flow closed")
    (if-let [error (:error result)]
      (throw (ex-info (:message error) (:data error)))
      result)))

(defn- animated-solve! [entry changes]
  (let [initial (step! entry changes 0)]
    (loop [used 0 frames [{:iteration 0 :nodes (:nodes initial)}] deltas (:deltas initial)]
      (let [budget (min 10 (- 300 used))
            next (step! entry {} budget)
            spent (get-in next [:metrics :iterations])
            used (+ used spent)
            frames (conj frames {:iteration used :nodes (:nodes next)})
            deltas (into deltas (:deltas next))]
        (if (or (>= used 300) (< spent budget) (<= (get-in next [:metrics :max-displacement]) 0.05))
          (let [net-deltas (reduce (fn [m [node weight]] (update m node (fnil + 0) weight)) {} deltas)]
            (assoc next :frames frames :iterationsUsed used
                   :deltas (vec (remove #(zero? (second %)) net-deltas))
                   :metrics (assoc (:metrics next) :iterations used
                                   :distance-sources-recomputed (get-in initial [:metrics :distance-sources-recomputed]))))
          (recur used frames deltas))))))

(defn compute! [registry {:keys [pane generation parent animate] :as request}]
  (let [request-data (normalize-request request)
        identity [generation parent]
        previous (get @registry pane)
        _ (when (and previous (not= identity (:identity previous))) (close-pane! registry pane))
        entry (or (get @registry pane) (create-entry identity))]
    (swap! registry assoc pane entry)
    (let [transaction (changes entry request-data)
          result (if (true? animate) (animated-solve! entry transaction) (step! entry transaction 300))]
      (swap! registry assoc pane (assoc entry :nodes (:nodes result) :edges (:edges request-data)))
      result)))
