(ns examples.lain.amb.enumeration-benchmark
  "All-answer comparison over one shared TTMS constraint declaration per bound."
  (:require [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.checkpoint-benchmark :as measure]
            [examples.lain.amb.checkpoint-enumeration :as checkpoint]
            [examples.lain.amb.checkpoint-question :as question]
            [examples.lain.amb.parallel-benchmark :as profiling]
            [examples.lain.amb.repair-enumeration :as repair]
            [examples.lain.amb.strategy-benchmark :as predicate]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.experimental.amb.observation :as observation]))

(defn profile-worker [built state] (profiling/profile #(checkpoint/evaluate built state)))

(defn sample [run threaded?]
  (let [result (profiling/profile run) parent (:profile result)
        profiles (mapv :profile (:branches result))
        counts (into [(:metrics parent)] (map :metrics) profiles)
        allocation (map :allocated-bytes (if threaded? (cons parent profiles) [parent]))
        fronts (if (seq (:branches result)) (map :frontier (:branches result)) [(:frontier result)])]
    {:status (:status result) :answers (:answers result) :run-ms (:run-ms parent)
     :allocated-bytes (if (every? some? allocation) (reduce + allocation) nil)
     :metrics (merge {:activations (reduce + (map :activations counts))
                      :peak-env (apply max (map #(get % :peak-env 0) counts))
                      :predicate-checks (reduce + (map :predicate-checks counts))
                      :leaves (if (every? some? fronts) (reduce + (map :leaves fronts)) nil)
                      :execution-threads (count (set (map :thread (if (seq profiles) profiles [parent]))))}
                     (:metrics result))
     :branches (mapv (fn [index branch]
                       {:range (nth (:ranges result) index) :answers (:answers branch)
                        :status (:status branch) :profile (:profile branch)})
                     (range) (:branches result))}))

(defn run-repair [built]
  (let [result (repair/run built)
        records (map #(observation/cell-base (:network result) %) (:controls built))]
    (assoc result :metrics {:repairs (reduce + (map #(quot (dec (:timestamp %)) 2) records))
                            :logical-nogoods (count (:logical-nogoods result))
                            :answer-exclusions (count (:answer-exclusions result))})))

(defn checked [expected result]
  (if (and (= :completed (:status result)) (= expected (:answers result)))
    result
    (throw (ex-info "Enumeration differs from exhaustive oracle"
                    {:expected expected :result result}))))

(defn summarize [samples]
  (assoc (dissoc (measure/summarize samples) :solution)
         :answers (:answers (first samples)) :answer-count (count (:answers (first samples)))))

(defn compare-case [n pool repeats]
  (let [started (System/nanoTime)
        shared (question/build n predicate/counted-predicate)
        question-ms (/ (- (System/nanoTime) started) 1e6)
        c-start (System/nanoTime) c (checkpoint/template shared)
        checkpoint-ms (/ (- (System/nanoTime) c-start) 1e6)
        r-start (System/nanoTime) r (repair/extend-question shared)
        repair-ms (/ (- (System/nanoTime) r-start) 1e6)
        expected (vec (sort (oracle/exhaustive n)))
        strategies [{:mode :repair :threaded? false :run #(run-repair r)}
                    {:mode :checkpoint :threaded? false :run #(checkpoint/run c)}
                    {:mode :partitioned-sequential :threaded? false
                     :run #(checkpoint/run-partitions c 4 nil profile-worker)}
                    {:mode :partitioned-parallel :threaded? true
                     :run #(checkpoint/run-partitions c 4 pool profile-worker)}]]
    (doseq [{:keys [run threaded?]} strategies] (checked expected (sample run threaded?)))
    (let [rows (reduce (fn [rows index]
                         (reduce (fn [rows {:keys [mode run threaded?]}]
                                   (let [result (checked expected (sample run threaded?))]
                                     (binding [*out* *err*]
                                       (prn {:domain n :mode mode :answer-count (count (:answers result))
                                             :ms (:run-ms result)}))
                                     (update rows mode (fnil conj []) result))) rows
                                 (if (even? index) strategies (reverse strategies))))
                       {} (range repeats))
          modes (into {} (map (fn [[mode samples]] [mode (summarize samples)])) rows)
          ks [:activations :predicate-checks :leaves]]
      (if (apply = (map #(select-keys (:metrics %) ks)
                       (concat (:partitioned-sequential rows) (:partitioned-parallel rows))))
        {:domain n :question-setup-ms question-ms :checkpoint-extension-ms checkpoint-ms
         :repair-extension-ms repair-ms :modes modes}
        (throw (ex-info "Partition concurrency changed enumeration work" {:domain n}))))))

(defn -main [& args]
  (let [repeats (if (seq args) (Long/parseLong (first args)) 3)]
    (if (pos? repeats)
      (let [pool (driver/pool 4)]
        (try
          (prn {:experiment :all-answer-network-composition :date "2026-10-13"
                :samples-per-mode repeats :warmups-per-mode 1 :workers 4 :cache :disabled
                :java-version (System/getProperty "java.version")
                :available-processors (.availableProcessors (Runtime/getRuntime))
                :cases (mapv #(compare-case % pool repeats) [4 8 12])})
          (finally (driver/close! pool))))
      (throw (ex-info "Positive sample count required" {:samples repeats})))))
