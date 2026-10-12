(ns examples.lain.relational-datalog.benchmark
  "Matched-strategy measurements. Counter atoms are observers, never network data."
  (:require [examples.lain.relational-datalog.experiment :as experiment]
            [examples.lain.relational-datalog.runtime :as relation]
            [examples.lain.relational-datalog.semi-naive :as semi]
            [examples.lain.relational-datalog.transitive :as transitive]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.runner :as runner]))

(defn record-evaluation! [counts propagator evaluated]
  (let [{:keys [messages effects]} (patch/normalize-activation-return (:activation-result evaluated))
        product? (contains? #{:examples.lain.relational-datalog.semi-naive/product
                              :examples.lain.relational-datalog.semi-naive/naive-product}
                            (:name propagator))
        ;; Both experimental products emit the result first, followed by progress.
        ;; Propagator records do not contain their boundary IDs; those live in graph.
        pairs (if (and product? (seq messages))
                (count (relation/facts (message/message-value (first messages))))
                0)
        declarations (count (filter #(and (= :network/declare-propagator (:op %))
                                         (= :examples.lain.relational-datalog.operators/publish (:name %)))
                                    effects))]
    (swap! counts #(-> % (update :pairs + pairs) (update :callback-declarations + declarations)))))

(defn measured-evaluator [counts]
  (fn [id network {:keys [success fail]}]
    (runner/evaluate-propagator
     id network
     {:fail fail
      :success (fn [evaluated current]
                 (record-evaluation! counts (net/network-env-lookup network id) evaluated)
                 (success evaluated current))})))

(defn sample [initial edges delivery]
  (let [counts (atom {:pairs 0 :callback-declarations 0})
        initial (assoc initial :run-options {:evaluate-propagator (measured-evaluator counts)})
        started (System/nanoTime)
        runs (case delivery
               :batch [(experiment/add-edges initial edges)]
               :incremental (rest (reductions #(experiment/add-edges %1 [%2]) initial edges))
               (throw (ex-info "Unknown delivery mode" {:delivery delivery})))
        runs (vec runs)
        elapsed (/ (- (System/nanoTime) started) 1e6)
        state (last runs)
        facts (transitive/tuples state :reach)]
    (if (= facts (transitive/closure edges))
      (merge @counts {:ms elapsed :steps (reduce + (map :steps runs))
                      :nodes (count (experiment/topology state)) :facts (count facts)})
      (throw (ex-info "Benchmark did not reach the specified closure" {:facts facts :edges edges})))))

(def fixtures
  [{:name :chain :edges [[:a :b] [:b :c] [:c :d]] :delivery :batch}
   {:name :diamond :edges [[:a :b] [:a :c] [:b :d] [:c :d]] :delivery :batch}
   {:name :cycle :edges [[:a :b] [:b :c] [:c :a]] :delivery :batch}
   {:name :late-bridge :edges [[:a :b] [:c :d] [:b :c]] :delivery :incremental}])

(defn median [values]
  (nth (sort values) (quot (count values) 2)))

(defn summarize [strategy samples]
  {:strategy strategy :samples (count samples)
   :median-ms (median (map :ms samples))
   :steps (mapv :steps samples) :pairs (mapv :pairs samples)
   :callback-declarations (mapv :callback-declarations samples)
   :nodes (mapv :nodes samples) :facts (mapv :facts samples)})

(defn compare-strategies [fixture initializations]
  (let [{:keys [name edges delivery]} fixture
        strategies [:naive :semi-naive]
        _ (doseq [_ (range 2) strategy strategies]
            (sample (get initializations strategy) edges delivery))
        ;; Alternate order so one mode does not always receive the warmer JVM.
        samples (reduce (fn [all index]
                          (let [order (if (even? index) strategies (reverse strategies))]
                            (reduce (fn [acc strategy]
                                      (update acc strategy conj
                                              (sample (get initializations strategy) edges delivery)))
                                    all order)))
                        {:naive [] :semi-naive []} (range 7))]
    {:case name :delivery delivery
     :results (mapv #(summarize % (get samples %)) strategies)}))

(defn -main [& args]
  (if (empty? args)
    (try
      (let [initializations (into {} (for [strategy [:naive :semi-naive]]
                                      [strategy (experiment/initialize transitive/source
                                                                       {:bindings (semi/bindings strategy)})]))]
        (doseq [fixture fixtures]
          (prn (compare-strategies fixture initializations))))
      (finally (shutdown-agents)))
    (throw (ex-info "Benchmark takes no arguments" {:args args}))))
