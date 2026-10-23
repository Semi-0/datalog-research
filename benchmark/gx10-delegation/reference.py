"""Known-correct host fixtures for the independent evaluator, never supplied to agents."""
SOURCES = {
'normalize': '''(ns delegation.normalize)
(defn normalize-events [events]
  (mapv (fn [i event]
          (let [valid? (and (vector? event)
                            (case (first event)
                              :put (= 3 (count event))
                              :delete (= 2 (count event))
                              :increment (and (= 3 (count event)) (integer? (nth event 2)))
                              false))]
            (when-not valid?
              (throw (ex-info "invalid event" {:type :invalid-event :index i :event event})))
            event)) (range) events))
''',
'project': '''(ns delegation.project)
(defn project-events [initial events]
  (when-not (map? initial) (throw (ex-info "invalid state" {:type :invalid-state})))
  (reduce (fn [state [i event]]
            (let [[op k v] event]
              (case op
                :put (assoc state k v)
                :delete (dissoc state k)
                :increment (let [old (get state k 0)]
                             (when-not (integer? old)
                               (throw (ex-info "invalid target" {:type :invalid-event :index i :event event})))
                             (assoc state k (+ old v))))))
          initial (map-indexed vector events)))
''',
'order': '''(ns delegation.order)
(defn dependency-order [nodes edges]
  (let [index (zipmap nodes (range))
        edges (set edges)
        _ (doseq [[a b] edges]
            (when-not (and (contains? index a) (contains? index b))
              (throw (ex-info "unknown" {:type :unknown-node}))))
        successors (reduce (fn [m [a b]] (update m (index a) (fnil conj []) (index b))) {} edges)
        indegree (reduce (fn [v [_ b]] (update v (index b) inc)) (vec (repeat (count nodes) 0)) edges)
        ready (into (sorted-set) (filter #(zero? (indegree %)) (range (count nodes))))]
    (loop [ready ready degree indegree result []]
      (if (empty? ready)
        (if (= (count result) (count nodes)) result (throw (ex-info "cycle" {:type :cycle})))
        (let [i (first ready)
              [ready degree] (reduce (fn [[r d] j]
                                       (let [d (update d j dec)]
                                         [(if (zero? (d j)) (conj r j) r) d]))
                                     [(disj ready i) degree] (get successors i []))]
          (recur ready degree (conj result (nodes i))))))))
''',
'batch': '''(ns delegation.batch
  (:require [delegation.normalize :as n] [delegation.project :as p] [delegation.order :as o]))
(defn execute-batch [initial jobs edges]
  (let [initial (p/project-events initial [])
        ids (mapv :id jobs)
        ordered (o/dependency-order ids edges)
        by-id (into {} (map (juxt :id identity) jobs))]
    {:order ordered
     :state (reduce (fn [state id] (p/project-events state (n/normalize-events (:events (get by-id id))))) initial ordered)}))
'''}
