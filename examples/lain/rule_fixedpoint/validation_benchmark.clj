(ns examples.lain.rule-fixedpoint.validation-benchmark
  "Matched validation costs, separate from the original six-strategy matrix."
  (:require [examples.lain.rule-fixedpoint.benchmark :as benchmark]
            [examples.lain.rule-fixedpoint.engine :as engine]
            [examples.lain.rule-fixedpoint.fixtures :as fixtures]
            [examples.lain.rule-fixedpoint.limits :as limits]
            [examples.lain.rule-fixedpoint.solvers :as solvers]
            [examples.lain.rule-fixedpoint.validation-reference :as reference]))

(defn matcher [variant]
  (case variant
    :compiled-reference #(reference/matching-result :indexed-delta %)
    :unconditional-fast #(if (nil? (get-in % [:rule :guard]))
                           (solvers/matching-result :indexed-delta % {})
                           (reference/matching-result :indexed-delta %))
    :batched-guards #(solvers/matching-result :indexed-delta % {})
    (throw (ex-info "Unknown validation variant" {:variant variant}))))

(defn execute [fixture prepared variant scope]
  (let [options (assoc (benchmark/options fixture (:templates prepared) :indexed-delta)
                       :match (matcher variant)
                       :max-ms 31000 :max-steps 1000001)
        built (engine/prepare (:rules prepared) options (:sources fixture))
        built (assoc-in built [:options :evaluate-propagator]
                        (limits/evaluator scope (vals (get-in built [:cells :relations]))))
        result (engine/run (engine/seed built))]
    (if (= :completed (:status result))
      (let [actual (into {} (map (fn [[predicate _]] [predicate (engine/facts result predicate)]))
                         (:expected fixture))]
        (if (= actual (:expected fixture))
          {:status :completed :answers (update-vals actual count) :metrics (engine/metrics result)}
          (throw (ex-info "Validation variant differs from independent oracle"
                          {:case (:id fixture) :variant variant}))))
      (throw (ex-info "Validation benchmark did not complete" {:status (:status result)} (:error result))))))

(defn compare-case [fixture prepared variants repeats]
  (let [run (fn [variant]
              (let [sample (benchmark/measure #(execute fixture prepared variant %))]
                (binding [*out* *err*]
                  (prn {:case (:id fixture) :variant variant :status (:status sample) :ms (:run-ms sample)}))
                sample))
        warmups (into {} (map (fn [variant] [variant (run variant)])) variants)
        active (filterv #(= :completed (:status (get warmups %))) variants)
        samples (reduce (fn [rows index]
                          (reduce (fn [rows variant]
                                    (if (every? #(= :completed (:status %)) (get rows variant []))
                                      (update rows variant (fnil conj []) (run variant)) rows))
                                  rows (if (even? index) active (reverse active)))) {} (range repeats))]
    {:case (:id fixture) :expected (update-vals (:expected fixture) count)
     :modes (into {} (map (fn [variant]
                           [variant (benchmark/summarize (get warmups variant) (get samples variant []))])) variants)}))

(defn guarded-cases []
  (let [arithmetic {:id :guarded-selective :domain [1 2 3 4]
                    :sources {:a {:predicate :left :tuple [1 2]}
                              :b {:predicate :right :tuple [2 3]}
                              :c {:predicate :right :tuple [2 4]}}
                    :expected {:answer #{[1 3]}}}]
    [[arithmetic (benchmark/prepare-catalog :selective_guard)]]))

(defn run [stage repeats]
  (let [variants (case stage
                   :unconditional [:compiled-reference :unconditional-fast]
                   :guards [:compiled-reference :unconditional-fast :batched-guards]
                   (throw (ex-info "Unknown benchmark stage" {:stage stage})))
        reach (benchmark/prepare-catalog :reach) dataflow (benchmark/prepare-catalog :dataflow)
        cases [[(fixtures/chain 8) reach]
               [(fixtures/graph-case :cycle-4 (range 4) [[0 1] [1 2] [2 3] [3 0]]) reach]
               [(fixtures/dataflow) dataflow]]
        cases (if (= :guards stage) (into cases (guarded-cases)) cases)]
    (if (pos? repeats)
      {:experiment :indexed-delta-validation :stage stage :warmups 1 :samples-per-mode repeats
       :date "2026-10-14" :cache :disabled :limits limits/defaults
       :java (System/getProperty "java.version") :processors (.availableProcessors (Runtime/getRuntime))
       :heap-limit-bytes (.maxMemory (Runtime/getRuntime))
       :source-sha256 (benchmark/source-hashes)
       :setup-ms (mapv (fn [[fixture prepared]] [(:id fixture) (:setup-ms prepared)]) cases)
       :allocation :cumulative-main-thread-not-peak
       :cases (mapv (fn [[fixture prepared]] (compare-case fixture prepared variants repeats)) cases)}
      (throw (ex-info "Positive repeat count required" {:repeats repeats})))))

(defn -main [& [path stage repeats]]
  (try
    (let [result (run (if stage (keyword stage) :guards) (if repeats (Long/parseLong repeats) 5))]
      (if path (spit path (str (pr-str result) "\n")) (prn result))
      (if (every? #(= :completed (:status %)) (mapcat (comp vals :modes) (:cases result)))
        nil
        (throw (ex-info "Validation benchmark has incomplete or failed rows" {:path path}))))
    (finally (shutdown-agents))))
