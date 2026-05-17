(ns differential-leapfrog
  (:require [clojure.core.async :as a]
            [differential-dataflow.frontier :as f]
            [differential-dataflow.versioned-core :as vc]
            [differential-dataflow.versioned-graph :as vg]
            [differential-leapfrog.trie :as trie]
            [leapfrog :as lf]))

;; Weighted facts: {pred {tuple integer-weight}}.
;; A tuple is visible when its final weight is positive. Negative deltas retract
;; support, and positive recursive rules maintain derivation counts.

(defn plus [a b]
  (let [n (+ (long (or a 0)) (long b))]
    (when-not (zero? n) n)))

(defn clean-rel [rel]
  (into {} (filter (comp some? val) rel)))

(defn add-weighted [a b]
  (clean-rel (merge-with plus a b)))

(defn add-db [db delta]
  (into {}
        (keep (fn [pred]
                (let [rel (add-weighted (get db pred {}) (get delta pred {}))]
                  (when (seq rel) [pred rel]))))
        (distinct (concat (keys db) (keys delta)))))

(defn neg-db [db]
  (into {} (map (fn [[p rel]] [p (into {} (map (fn [[t w]] [t (- w)]) rel))])) db))

(defn diff-db [new old]
  (add-db new (neg-db old)))

(defn support [db pred]
  (set (for [[tuple weight] (get db pred {})
             :when (pos? weight)]
         tuple)))

(defn- db->rows [db]
  (vec (for [[pred rel] db
             [tuple weight] rel]
         [[pred tuple] weight])))

(defn- rows->db [rows]
  (reduce (fn [db [[pred tuple] weight]]
            (add-db db {pred {tuple weight}}))
          {}
          rows))

(defn- compact-rows [rows]
  (if (empty? rows)
    []
    (let [in (a/chan 4)
          out ((vg/consolidate) in)]
      (a/>!! in [:data 0 rows])
      (a/>!! in [:frontier #{1}])
      (let [[_ _ rows'] (a/<!! out)]
        (a/close! in)
        rows'))))

(defn- compact-db [db]
  (rows->db (compact-rows (db->rows db))))

(defn- db->tries [db]
  (into {}
        (map (fn [[pred rel]]
               [pred (trie/from-weighted-rel rel)]))
        db))

(defn- source-trie [source pred]
  (let [rel (get source pred)]
    (cond
      (trie/trie? rel) rel
      (map? rel) (trie/from-weighted-rel rel)
      :else (trie/empty-trie))))

(defn- source-weight [source pred tuple]
  (let [rel (get source pred)]
    (cond
      (trie/trie? rel) (trie/weight-at rel tuple)
      (map? rel) (get rel tuple 0)
      :else 0)))

(defn- add-arrangements [arrangements delta]
  (reduce-kv
   (fn [acc pred rel]
     (update acc pred #(trie/add-tuples (or % (trie/empty-trie)) rel)))
   arrangements
   delta))

(defn- atom-form [atom]
  (cond
    (map? atom) (update atom :kind #(or % :pos))
    (vector? atom) {:kind :pos :atom atom}
    :else (throw (ex-info "invalid rule atom" {:atom atom}))))

(defn- normalize-rule [rule]
  (update rule :body #(mapv atom-form %)))

(defn- head-pred [rule]
  (first (:head rule)))

(defn- positive-rule? [rule]
  (and (nil? (:aggregate rule))
       (every? #(= :pos (:kind %)) (:body rule))))

(defn- body-vars [body]
  (vec (distinct (mapcat (comp second :atom) body))))

(defn- env-tuple [vars env]
  (mapv env vars))

(defn- tuple-matches-env? [vars env tuple]
  (every? (fn [[var value]]
            (or (not (contains? env var)) (= (get env var) value)))
          (map vector vars tuple)))

(defn- relation-from-source [source {[pred vars] :atom}]
  (lf/make-relation pred vars (trie/nonzero-tuples (source-trie source pred))))

(defn- assert-valid-join! [rels join-vars ctx]
  (when (lf/validate-join-order rels join-vars)
    (throw (lf/invalid-join-order-ex rels join-vars ctx))))

(defn weighted-lftj
  "Join positive atoms with LFTJ and multiply source tuple weights."
  [sources body]
  (let [atoms (mapv atom-form body)
        join-vars (body-vars atoms)
        rels (mapv #(relation-from-source %1 %2) sources atoms)
        _ (assert-valid-join! rels join-vars {:body body :atoms atoms})]
    (for [tuple (lf/lftj rels join-vars :tuples)
          :let [env (zipmap join-vars tuple)
                weight (reduce
                        (fn [acc [source {[pred vars] :atom}]]
                          (* acc (long (source-weight source pred (env-tuple vars env)))))
                        1
                        (map vector sources atoms))]
          :when (not (zero? weight))]
      [env weight])))

(defn project-weighted [rule weighted-envs]
  (let [[pred vars] (:head rule)]
    {pred
     (reduce (fn [rel [env weight]]
               (add-weighted rel {(env-tuple vars env) weight}))
             {}
             weighted-envs)}))

(defn- project-envs [rule envs]
  (let [[pred vars] (:head rule)]
    {pred (frequencies (map #(env-tuple vars %) envs))}))

(defn- join-envs [db body]
  (let [atoms (mapv atom-form body)
        pos (filterv #(= :pos (:kind %)) atoms)
        neg (filterv #(= :not (:kind %)) atoms)
        join-vars (body-vars pos)
        rels (mapv (fn [{[pred vars] :atom}]
                     (lf/make-relation pred vars (support db pred)))
                   pos)
        _ (assert-valid-join! rels join-vars {:body body :positive pos})]
    (for [tuple (lf/lftj rels join-vars :tuples)
          :let [env (zipmap join-vars tuple)]
          :when (every? (fn [{[pred vars] :atom}]
                          (not-any? #(tuple-matches-env? vars env %) (support db pred)))
                        neg)]
      env)))

(defn- project-snapshot [db rule]
  (let [envs (join-envs db (:body rule))]
    (if-let [{:keys [op group-var value-var]} (:aggregate rule)]
      {(head-pred rule)
       (into {}
             (for [[k rows] (group-by group-var envs)
                   :let [v (case op
                             :count (count rows)
                             :sum (reduce + (map value-var rows)))]]
               [[k v] 1]))}
      (project-envs rule envs))))

(defn- source-has-delta? [source pred]
  (seq (trie/nonzero-tuples (source-trie source pred))))

(defn- rule-delta [old-db delta-db new-db rule]
  (let [rule (normalize-rule rule)
        body (:body rule)]
    (reduce
     add-db
     {}
     (for [i (range (count body))
           :let [{[pred _] :atom} (nth body i)]
           :when (source-has-delta? delta-db pred)]
       (let [sources (mapv (fn [idx {[pred _] :atom}]
                             (cond
                               (< idx i) new-db
                               (= idx i) delta-db
                               :else old-db))
                           (range)
                           body)]
         (project-weighted rule (weighted-lftj sources body)))))))

(defn- derive-delta [old-db delta-db new-db rules]
  (reduce add-db {} (map #(rule-delta old-db delta-db new-db %) rules)))

(defn- inner-step [version]
  (when (vector? version)
    (peek version)))

(defn- rule-loop-delta [state version]
  (let [raw (compact-db (get-in state [:exact version]))
        top (f/version-truncate version)]
    (if (= 1 (inner-step version))
      (add-db raw (get-in state [:initial top] {}))
      raw)))

(defn- flush-rule-version [rules [state messages] version]
  (let [delta (rule-loop-delta state version)
        old-arr (:facts state)
        new-arr (add-arrangements old-arr delta)
        derived (derive-delta old-arr (db->tries delta) new-arr rules)
        rows (db->rows derived)]
    [(-> state
         (assoc :facts new-arr)
         (update :todo disj version))
     (cond-> messages
       (seq rows) (conj (vc/data version rows)))]))

(defn- flush-rule-versions [state frontier rules]
  (let [closed (sort (remove #(f/frontier-lte-version? frontier %) (:todo state)))]
    (reduce (partial flush-rule-version rules) [state []] closed)))

(defn- iterative-rule-operator [old-db rules]
  (letfn [(step [state msg]
            (case (first msg)
              :data (let [delta (rows->db (vc/rows msg))
                          top (f/version-truncate (vc/version msg))]
                      (cond-> (-> state
                                  (update-in [:exact (vc/version msg)] add-db delta)
                                  (update :todo conj (vc/version msg)))
                        (= 0 (inner-step (vc/version msg))) (update-in [:initial top] add-db delta)))
              :frontier (let [[state' _messages] (flush-rule-versions state (vc/frontier-value msg) rules)]
                          (first (vc/advance-frontier state' (vc/frontier-value msg))))
              state))
          (emit [old-state _state msg]
            (case (first msg)
              :frontier (let [[state' messages] (flush-rule-versions old-state (vc/frontier-value msg) rules)]
                          (into messages (second (vc/advance-frontier state' (vc/frontier-value msg)))))
              []))]
    (vc/unary-operator
     {:facts (db->tries old-db)
      :exact {}
      :initial {}
      :todo #{}
      :out-frontier nil}
     step
     emit
     16)))

(defn- drain-versioned-output [out]
  (loop [change {}]
    (let [[msg port] (a/alts!! [out (a/timeout 200)])]
      (if-not (= port out)
        change
        (let [[kind _ rows] msg]
          (case kind
            :data (recur (add-db change (rows->db rows)))
            :frontier (recur change)
            nil change
            (throw (ex-info "unexpected versioned message" {:message msg}))))))))

(defn- derive-delta-via-iterate [old-db delta-db rules]
  (if (or (empty? delta-db) (empty? rules))
    {}
    (let [in (a/chan 4)
          out ((vg/iterate (fn [loop-in]
                             ((iterative-rule-operator old-db rules) loop-in))
                           16)
               in)]
      (a/>!! in [:data 0 (db->rows delta-db)])
      (a/>!! in [:frontier #{1}])
      (a/close! in)
      (let [change (compact-db (drain-versioned-output out))]
        change))))

(defn- positive-step [facts delta rules]
  (derive-delta-via-iterate facts delta rules))

(defn- positive-fixpoint [facts delta rules]
  [facts (positive-step facts (compact-db delta) rules)])

(defn- stratum-rules [rules]
  (let [rules (mapv normalize-rule rules)
        heads (set (map head-pred rules))]
    (loop [strata (zipmap heads (repeat 0))
           n 0]
      (let [next-strata
            (reduce
             (fn [s rule]
               (let [h (head-pred rule)
                     aggregate? (some? (:aggregate rule))]
                 (reduce
                  (fn [s {[p _] :atom kind :kind}]
                    (if-not (contains? heads p)
                      s
                      (let [strict? (or aggregate? (= :not kind))
                            required (+ (get s p 0) (if strict? 1 0))]
                        (update s h max required))))
                  s
                  (:body rule))))
             strata
             rules)]
        (cond
          (= next-strata strata)
          (->> rules
               (group-by #(get next-strata (head-pred %)))
               (sort-by key)
               (mapv (fn [[_ rs]] (vec rs))))

          (> n (* 2 (max 1 (count heads)) (max 1 (count rules))))
          (throw (ex-info "cycle through negation or aggregation"
                          {:rules rules :strata next-strata}))

          :else
          (recur next-strata (inc n)))))))

(defn- recompute-stratum [db old-idb rules]
  (let [heads (set (map head-pred rules))
        new (select-keys (reduce add-db {} (map #(project-snapshot db %) rules)) heads)]
    [new (diff-db new (select-keys old-idb heads))]))

(defn- apply-strata [old-db edb* idb rules initial-delta]
  (loop [lower-change {}
         strata (stratum-rules rules)]
    (if (empty? strata)
      [(add-db idb lower-change) lower-change]
      (let [rules (first strata)
            positive (filterv positive-rule? rules)
            views (filterv (complement positive-rule?) rules)
            delta (add-db initial-delta lower-change)
            [_ pos-change] (positive-fixpoint old-db delta positive)
            idb-before-views (add-db idb (add-db lower-change pos-change))
            db-with-edb (add-db edb* idb-before-views)
            [view-new view-change] (recompute-stratum db-with-edb idb views)
            stratum-change (add-db pos-change view-change)]
        (recur (add-db lower-change stratum-change)
               (rest strata))))))

(defn compact-history [history frontier]
  (let [{old true new false} (group-by #(<= (:time %) frontier) history)
        summary (reduce add-db {} (map :delta old))]
    (cond-> new
      (seq summary) (conj {:time frontier :delta summary :compact? true}))))

(defn transact [{:keys [edb idb time history rules idb-preds] :as system} delta]
  (let [rules (cond->> (mapv normalize-rule rules)
                (seq idb-preds) (filterv #(contains? idb-preds (head-pred %))))
        time* (inc (or time 0))
        old-db (add-db edb idb)
        edb* (add-db edb delta)
        [idb* idb-change] (apply-strata old-db edb* idb rules delta)
        change (add-db delta idb-change)]
    (assoc system
           :edb edb*
           :idb idb*
           :time time*
           :history (conj (vec history) {:time time* :delta change}))))

(defn view [system]
  (add-db (:edb system) (:idb system)))

(def path-rules
  [{:head [:path [:x :y]]
    :body [{:atom [:edge [:x :y]]}]}
   {:head [:path [:x :z]]
    :body [{:atom [:path [:x :y]]}
           {:atom [:edge [:y :z]]}]}])

(def small-rules
  [{:head [:missing-edge-target [:x]]
    :body [{:atom [:node [:x]]}
           {:kind :not :atom [:edge [:x :y]]}]}
   {:head [:out-degree [:x :n]]
    :body [{:atom [:edge [:x :y]]}]
    :aggregate {:op :count :group-var :x}}])

(def system0
  {:edb {}
   :idb {}
   :time 0
   :history []
   :rules path-rules
   :idb-preds #{:path}})

(def system1
  (transact system0 {:edge {[1 2] 1
                            [2 3] 1
                            [3 4] 1}}))

(def system2
  (transact system1 {:edge {[4 5] 1}}))

(def system3
  (transact system2 {:edge {[2 3] -1}}))

(def small-system
  (transact {:edb {}
             :idb {}
             :time 0
             :history []
             :rules small-rules
             :idb-preds #{:missing-edge-target :out-degree}}
            {:node {[1] 1 [2] 1}
             :edge {[1 2] 1}}))

(defn -main [& _]
  (println :initial-paths (sort (support (view system1) :path)))
  (println :after-insert-delta (get-in (last (:history system2)) [:delta :path]))
  (println :after-delete-paths (sort (support (view system3) :path)))
  (println :negation-and-aggregation (:idb small-system))
  (println :compacted-history (compact-history (:history system3) 2)))
