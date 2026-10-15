(ns examples.lain.rule-fixedpoint.benchmark
  "Bounded, matched end-to-end and frozen-rule measurements. Raw samples are EDN."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [examples.lain.amb.checkpoint-benchmark :as allocation]
            [examples.lain.amb.parallel-benchmark :as profiling]
            [examples.lain.amb.search-budget :as budget]
            [examples.lain.amb.solver-strategy :as strategy]
            [examples.lain.amb.worker-driver :as driver]
            [examples.lain.rule-fixedpoint.catalog :as catalog]
            [examples.lain.rule-fixedpoint.engine :as engine]
            [examples.lain.rule-fixedpoint.fixtures :as fixtures]
            [examples.lain.rule-fixedpoint.limits :as limits]
            [examples.lain.rule-fixedpoint.model :as model]
            [examples.lain.rule-fixedpoint.query :as query]
            [examples.lain.rule-fixedpoint.solvers :as solvers]
            [propagators.network-cache :as cache]
            [propagators.runner :as runner]))

(defn source-hashes []
  (let [files (filter #(and (.isFile %) (re-find #"\.(clj|lain)$" (.getName %)))
                      (file-seq (io/file "examples/lain/rule_fixedpoint")))]
    (into (sorted-map)
          (map (fn [file]
                 [(.getPath file)
                  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                        (.getBytes (slurp file) "UTF-8"))]
                    (apply str (map #(format "%02x" (bit-and 255 %)) digest)))])) files)))

(def ^:dynamic *record-case* (fn [_] nil))

(defn failure [error]
  {:message (.getMessage ^Throwable error)
   :causes (loop [current error messages []]
             (if (or (nil? current) (= 8 (count messages))) messages
                 (recur (.getCause ^Throwable current) (conj messages (.getMessage ^Throwable current)))))})

(defn measure [f]
  (let [scope (budget/scope limits/defaults) checks (atom 0) workers (atom 0)
        before (allocation/allocated-bytes) started (System/nanoTime)
        result (try
                 (binding [cache/*cache* nil query/*candidate-checks* checks
                           solvers/*worker-allocated* workers
                           profiling/*advance-transform* (limits/transform scope)
                           runner/*advance-transform* (limits/transform scope)]
                   (f scope))
                 (catch Throwable error {:status (if (limits/exhausted? error) :incomplete :failed)
                                         :error (failure error)}))
        elapsed (/ (- (System/nanoTime) started) 1e6) after (allocation/allocated-bytes)]
    (assoc result :run-ms elapsed :usage (budget/usage scope) :predicate-activations @checks
           :allocated-bytes (if (and before after) (+ (- after before) @workers) nil))))

(defn options [fixture templates mode]
  {:strategy mode :strategies (set solvers/modes) :domain (:domain fixture) :queries templates :workers 4
   :match #(solvers/matching-result (:strategy %) % {:workers 4})})

(defn end-to-end [fixture prepared mode scope]
  (let [built (engine/prepare (:rules prepared) (options fixture (:templates prepared) mode) (:sources fixture))
        evaluator (limits/evaluator scope (vals (get-in built [:cells :relations])))
        guarded (-> built (assoc-in [:options :evaluate-propagator] evaluator)
                    (assoc-in [:options :max-ms] 31000) (assoc-in [:options :max-steps] 1000001))
        result (engine/run (engine/seed guarded))]
    (if (= :completed (:status result))
      (let [actual (into {} (map (fn [[predicate _]] [predicate (engine/facts result predicate)])) (:expected fixture))]
        (if (= (:expected fixture) actual)
          {:status :completed :answers (update-vals actual count) :metrics (engine/metrics result)}
          (throw (ex-info "Rule closure differs from independent BFS oracle" {:case (:id fixture)}))))
      {:status (if (limits/exhausted? (:error result)) :incomplete :failed)
       :error (failure (:error result)) :metrics (engine/metrics result)})))

(defn frozen-request [fixture prepared]
  (let [rule (last (filter #(= 2 (count (:body %))) (:rules prepared)))
        source-tables (reduce (fn [tables {:keys [predicate tuple]}]
                                (update tables predicate (fnil conj #{}) tuple)) (:expected fixture) (vals (:sources fixture)))
        tables (mapv #(get source-tables (first %) #{}) (:body rule))
        deltas (mapv #(set (take-last 1 (sort-by pr-str %))) tables)]
    {:rule rule :query (get-in prepared [:templates (:id rule)]) :domain (:domain fixture)
     :variables (model/variables rule) :demand {} :tables tables :deltas deltas
     :initial? false :require-delta? true}))

(defn finite-oracle [request]
  ;; Independent finite enumeration, outside timing and never used to emit facts.
  (into #{}
        (for [x (:domain request) y (:domain request) z (:domain request)
              :when (model/accepts? request [x y z])]
          (zipmap (:variables request) [x y z]))))

(defn frozen [fixture prepared mode _scope]
  (let [request (:frozen-request prepared)
        expected (:frozen-expected prepared)
        result (solvers/matching-result mode request {:workers 4})]
    (if (= expected (:bindings result))
      {:status :completed :answers (count expected)
       :metrics {:join-probes (:probes result) :accepted-bindings (count (:bindings result))}}
      (throw (ex-info "Frozen rule matcher differs from occurrence-delta oracle" {:case (:id fixture) :mode mode})))))

(defn summarize [warmup samples]
  (let [complete? (and (= :completed (:status warmup)) (every? #(= :completed (:status %)) samples))
        ordered (sort (map :run-ms samples))]
    {:status (cond
               (some #(= :failed (:status %)) (cons warmup samples)) :failed
               complete? :completed
               :else :incomplete)
     :warmup warmup :samples samples
     :median-run-ms (if (and complete? (seq ordered)) (nth (vec ordered) (quot (count ordered) 2)) nil)}))

(defn compare-case [fixture prepared kind repeats]
  (let [execute (case kind :end-to-end end-to-end :frozen frozen
                      (throw (ex-info "Unknown benchmark kind" {:kind kind})))
        prepared (if (= kind :frozen)
                   (let [request (frozen-request fixture prepared)]
                     (assoc prepared :frozen-request request :frozen-expected (finite-oracle request)))
                   prepared)
        run (fn [mode]
              (let [result (measure #(execute fixture prepared mode %))]
                (binding [*out* *err*]
                  (prn {:case (:id fixture) :kind kind :mode mode :status (:status result) :ms (:run-ms result)})) result))
        warmups (into {} (map (fn [mode] [mode (run mode)])) solvers/modes)
        active (filterv #(= :completed (:status (get warmups %))) solvers/modes)
        samples (reduce (fn [samples index]
                          (reduce (fn [rows mode]
                                    (if (every? #(= :completed (:status %)) (get rows mode []))
                                      (update rows mode (fnil conj []) (run mode)) rows))
                                  samples (if (even? index) active (reverse active)))) {} (range repeats))]
    {:case (:id fixture) :kind kind :domain-size (count (:domain fixture))
     :expected (update-vals (:expected fixture) count)
     :modes (into {} (map (fn [mode] [mode (summarize (get warmups mode) (get samples mode []))])) solvers/modes)}))

(defn prepare-catalog [name]
  (let [started (System/nanoTime)
        compiled (catalog/compile-source (slurp (io/resource (str "examples/lain/rule_fixedpoint/" (clojure.core/name name) ".lain"))))
        options (solvers/attach (:rules compiled) {:strategy :indexed-full} compiled)]
    {:rules (:rules compiled) :templates (:queries options) :setup-ms (/ (- (System/nanoTime) started) 1e6)}))

(defn run [repeats selected]
  (let [catalogs (into {} (map (fn [name] [name (prepare-catalog name)])) [:reach :dataflow])
        cases (filterv #(or (empty? selected) (contains? selected (:id %))) (fixtures/cases))
        pool (driver/pool 4)]
    (if (and (pos? repeats) (seq cases))
      (try
        (binding [strategy/*executor* pool]
          {:experiment :rule-fixedpoint :date "2026-10-14" :samples-per-mode repeats :warmups 1
           :limits limits/defaults :cache :disabled :pool {:workers 4 :creation :outside-timing}
           :java (System/getProperty "java.version") :processors (.availableProcessors (Runtime/getRuntime))
           :heap-limit-bytes (.maxMemory (Runtime/getRuntime)) :source-sha256 (source-hashes)
           :setup-ms (update-vals catalogs :setup-ms)
           :allocation :cumulative-main-plus-successful-workers-not-peak
           :cases (vec (for [fixture cases kind [:end-to-end :frozen]]
                         (let [row (compare-case fixture (get catalogs (:catalog fixture)) kind repeats)]
                           (*record-case* row) row)))})
        (finally (driver/close! pool)))
      (do (driver/close! pool) (throw (ex-info "Positive repeats and known fixture names required" {}))))))

(defn -main [& [path repeats selected]]
  (try
    (let [completed (atom [])
          record (fn [row]
                   (let [rows (swap! completed conj row)]
                     (if path
                       (spit (str path ".partial.edn")
                             (str (pr-str {:matrix-status :partial :source-sha256 (source-hashes) :cases rows}) "\n"))
                       nil)))
          result (binding [*record-case* record]
                   (run (if repeats (Long/parseLong repeats) 5)
                        (if selected (set (map keyword (string/split selected #","))) #{})))]
      (if path (spit path (str (pr-str result) "\n")) (prn result))
      (if (some (fn [row] (some #(= :failed (:status %)) (vals (:modes row)))) (:cases result))
        (throw (ex-info "Benchmark contains failed correctness/execution checks; inspect raw results" {:path path}))
        nil))
    (finally (shutdown-agents))))
