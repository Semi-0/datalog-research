(ns differential-leapfrog
  (:require [leapfrog :as lf]))

;; Tiny differential-ish Datalog on top of leapfrog triejoin.
;; Relations are integer-weighted arrangements:
;;   {:edge {[1 2] 1, [2 3] -1}}
;; Positive weight means present; zero weights are compacted away.

(defn plus [a b]
  (let [n (+ (or a 0) b)]
    (when-not (zero? n) n)))

(defn clean-rel [rel]
  (into {} (filter (comp some? val) rel)))

(defn add-weighted [a b]
  (clean-rel (merge-with plus a b)))

(defn add-db [db delta]
  (into {}
        (keep (fn [pred]
                (let [rel (add-weighted (get db pred {})
                                        (get delta pred {}))]
                  (when (seq rel) [pred rel]))))
        (distinct (concat (keys db) (keys delta)))))

(defn diff-db [new old]
  (into {}
        (keep (fn [[pred tuples]]
                (let [d (add-weighted tuples
                                      (into {}
                                            (map (fn [[t w]] [t (- w)])
                                                 (get old pred {}))))]
                  (when (seq d) [pred d]))))
        new))

(defn support [db pred]
  (set (for [[tuple weight] (get db pred {})
             :when (pos? weight)]
         tuple)))

(defn tuple-matches-env? [vars env tuple]
  (every? (fn [[var value]]
            (or (not (contains? env var))
                (= (get env var) value)))
          (map vector vars tuple)))

(defn body-vars [body]
  (vec (distinct (mapcat #(second (:atom %)) body))))

(defn env-tuple [vars env]
  (mapv env vars))

(defn join-envs [db body]
  (let [pos       (filter #(= :pos (:kind % :pos)) body)
        neg       (filter #(= :not (:kind %)) body)
        join-vars (body-vars pos)
        rels      (mapv (fn [{[pred vars] :atom}]
                          (lf/make-relation pred vars (support db pred)))
                        pos)]
    (for [tuple (lf/lftj rels join-vars :tuples)
          :let [env (zipmap join-vars tuple)]
          :when (every? (fn [{[pred vars] :atom}]
                          (not-any? #(tuple-matches-env? vars env %)
                                    (support db pred)))
                        neg)]
      env)))

(defn project [db rule]
  (let [[head-pred head-vars] (:head rule)
        envs                  (join-envs db (:body rule))]
    (if-let [{:keys [op group-var value-var]} (:aggregate rule)]
      {head-pred
       (into {}
             (for [[k rows] (group-by group-var envs)
                   :let [v (case op
                             :count (count rows)
                             :sum   (reduce + (map value-var rows)))]]
               [[k v] 1]))}
      {head-pred
       (frequencies (map #(env-tuple head-vars %) envs))})))

(defn derive-once [db rules]
  (reduce add-db {} (map #(project db %) rules)))

(defn fixpoint [edb idb-preds rules]
  (loop [idb {}]
    (let [db   (add-db edb idb)
          idb* (select-keys (derive-once db rules) idb-preds)]
      (if (= idb idb*) idb* (recur idb*)))))

(defn compact-history [history frontier]
  (let [{old true new false} (group-by #(<= (:time %) frontier) history)
        summary             (reduce add-db {} (map :delta old))]
    (cond-> new
      (seq summary) (conj {:time frontier :delta summary :compact? true}))))

(defn transact [{:keys [edb idb time history rules idb-preds] :as system} delta]
  (let [time*  (inc (or time 0))
        edb*   (add-db edb delta)
        idb*   (fixpoint edb* idb-preds rules)
        change (add-db delta (diff-db idb* idb))]
    (assoc system
           :edb edb*
           :idb idb*
           :time time*
           :history (conj history {:time time* :delta change}))))

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

(println :initial-paths (sort (support (view system1) :path)))
(println :after-insert-delta (get-in (last (:history system2)) [:delta :path]))
(println :after-delete-paths (sort (support (view system3) :path)))
(println :negation-and-aggregation (:idb small-system))
(println :compacted-history (compact-history (:history system3) 2))
