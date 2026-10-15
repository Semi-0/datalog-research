(ns examples.lain.rule-fixedpoint.solvers
  "Only binding discovery changes. The rule, Lain guard and proof publisher do not."
  (:require [examples.lain.amb.concurrent-learning-checkpoint :as hybrid]
            [examples.lain.amb.solver-strategy :as strategy]
            [examples.lain.rule-fixedpoint.matching :as matching]
            [examples.lain.rule-fixedpoint.model :as model]
            [examples.lain.rule-fixedpoint.query :as query]))

(def modes [:repair :checkpoint :concurrent :concurrent-learning :indexed-full :indexed-delta])

(def ^:dynamic *worker-allocated* nil)

(defn worker-allocation [result]
  (let [values (map #(get-in % [:profile :allocated-bytes]) (:branches result))]
    (if (and *worker-allocated* (seq values) (every? some? values))
      (swap! *worker-allocated* + (reduce + values)) nil)))

(defn matching-result [mode request context]
  (cond
    (some empty? (:tables request)) {:bindings #{} :probes 0}
    ;; A guard-free unary copy is a direct projection for every strategy.
    (and (= 1 (count (get-in request [:rule :body]))) (nil? (get-in request [:rule :guard])))
    {:bindings (model/full-bindings (if (:require-delta? request) (assoc request :tables (:deltas request)) request))
     :probes (count (first (:tables request)))}
    :else
    (case mode
      (:indexed-full :indexed-delta)
      (let [result (matching/indexed mode request)]
        (if (or (nil? (get-in request [:rule :guard])) (empty? (:bindings result)))
          result
          (let [question (query/ready-question (:query request) request)
                accepted? (fn [binding]
                            (query/accepted-batch? question (query/encode request (model/padded request binding))))]
            (update result :bindings #(into #{} (filter accepted?) %)))))
      (:repair :checkpoint :concurrent :concurrent-learning)
      (let [question (query/instantiate (:query request) request)
            selected (if (= mode :concurrent-learning) hybrid/concurrent-solver (strategy/select mode))
            result (strategy/solve-question selected question context)]
        (worker-allocation result)
        (if (= :completed (:status result))
          {:bindings (into #{} (map #(zipmap (:variables request) (query/decode request %))) (:answers result))
           :probes 0}
          (throw (ex-info "Rule matching did not complete" {:mode mode :status (:status result)}))))
      (throw (ex-info "Unknown rule matcher" {:strategy mode})))))

(defn attach [rules options context]
  (if (contains? (set modes) (:strategy options))
    (let [templates (into {} (map (fn [rule] [(:id rule) (query/compile-template rule context)])) rules)]
      (assoc options :queries templates :strategies (set modes)
             :match #(matching-result (:strategy %) % {:workers (or (:workers options) 4)})))
    (throw (ex-info "Unknown rule matcher" {:strategy (:strategy options)}))))
