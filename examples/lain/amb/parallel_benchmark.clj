(ns examples.lain.amb.parallel-benchmark
  "Matched partition work, sequential versus threads; original first-answer reference."
  (:require [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.builder :as builder]
            [examples.lain.amb.checkpoint-benchmark :as measurement]
            [examples.lain.amb.checkpoint-question :as question]
            [examples.lain.amb.checkpoint-search :as checkpoint]
            [examples.lain.amb.domain-refinement :as domain]
            [examples.lain.amb.range-search :as range]
            [examples.lain.amb.strategy-benchmark :as previous]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.network-cache :as cache]
            [propagators.runner :as runner]))

(def ^:dynamic *advance-transform*
  "Optional experiment instrumentation, outside Net and solver policy."
  identity)

(defn profile [f]
  (let [counts (atom {:activations 0}) predicates (atom 0)
        before (measurement/allocated-bytes) started (System/nanoTime)
        result (binding [cache/*cache* nil previous/*predicate-checks* predicates
                         runner/*advance-transform*
                         (runner/compose-advance-transforms
                          *advance-transform* (checkpoint/counter-transform counts))] (f))
        elapsed (/ (- (System/nanoTime) started) 1e6)
        after (measurement/allocated-bytes)]
    (assoc result :profile
           {:run-ms elapsed :allocated-bytes (if (and before after) (- after before) nil)
            :metrics (assoc @counts :predicate-checks @predicates)
            :thread (.getName (Thread/currentThread))})))

(defn profile-worker [built state] (profile #(range/evaluate built state)))

(defn sample [run threaded?]
  (let [result (profile run) parent (:profile result)
        profiles (mapv :profile (:branches result))
        counters (into [(:metrics parent)] (map :metrics) profiles)
        allocation (map :allocated-bytes (if threaded? (cons parent profiles) [parent]))
        fronts (if (seq (:branches result)) (map :frontier (:branches result)) [(:frontier result)])]
    {:status (:status result) :solution (:solution result) :run-ms (:run-ms parent)
     :allocated-bytes (if (every? some? allocation) (reduce + allocation) nil)
     :metrics {:activations (reduce + (map :activations counters))
               :peak-env (apply max (map #(get % :peak-env 0) counters))
               :predicate-checks (reduce + (map :predicate-checks counters))
               :decisions (reduce + (map :decisions fronts))
               :leaves (reduce + (map :leaves fronts))
               :execution-threads (count (set (map :thread (if (seq profiles) profiles [parent]))))}
     :branches (mapv (fn [index branch]
                       {:range (nth (:ranges result) index) :status (:status branch)
                        :solution (:solution branch) :profile (:profile branch)})
                     (range) (:branches result))}))

(defn scenarios [template pools]
  (into [{:mode :original :threaded? false
          :run #(range/evaluate (:built template) (select-keys (:built template) [:network :tasks]))}]
        (for [workers [2 4] threaded? [false true]]
          {:mode (keyword (str (if threaded? "parallel-" "sequential-") workers))
           :threaded? threaded?
           :run #(range/run template workers (if threaded? (get pools workers) nil) profile-worker)})))

(defn checked [n result]
  (let [expected (:solution (oracle/chronological n))]
    (if (and (= expected (:solution result))
             (= (if expected :solution :exhausted) (:status result)))
      (do
        (doseq [branch (:branches result) :when (= :solution (:status branch))]
          (if (and (apply builder/pythagorean? (:solution branch))
                   (some #{(first (:solution branch))} (:range branch))) nil
              (throw (ex-info "Worker escaped its range or constraints" {:branch branch}))))
        result)
      (throw (ex-info "Parallel answer differs from oracle" {:domain n :result result})))))

(defn compare-case [kind n pools repeats]
  (let [started (System/nanoTime)
        question (case kind
                   :ttms (question/build n previous/counted-predicate)
                   :domain (domain/build n previous/counted-predicate)
                   (throw (ex-info "Unknown question kind" {:kind kind})))
        template (range/template question)
        setup (/ (- (System/nanoTime) started) 1e6)
        strategies (scenarios template pools)]
    (doseq [{:keys [run threaded?]} strategies] (checked n (sample run threaded?)))
    (let [rows (reduce
                (fn [rows index]
                  (reduce (fn [rows {:keys [mode run threaded?]}]
                            (let [result (checked n (sample run threaded?))]
                              (binding [*out* *err*]
                                (prn {:kind kind :domain n :mode mode :ms (:run-ms result)}))
                              (update rows mode (fnil conj []) result))) rows
                          (if (even? index) strategies (reverse strategies))))
                {} (range repeats))
          modes (into {} (map (fn [[mode samples]] [mode (measurement/summarize samples)])) rows)
          work-keys [:activations :predicate-checks :decisions :leaves]]
      (doseq [workers [2 4]]
        (let [sequential (get rows (keyword (str "sequential-" workers)))
              parallel (get rows (keyword (str "parallel-" workers)))
              counts (map #(select-keys (:metrics %) work-keys) (concat sequential parallel))]
          (if (apply = counts) nil
              (throw (ex-info "Sequential/threaded partition work differs" {:kind kind :domain n})))))
      {:kind kind :domain n :setup-ms setup :modes modes})))

(defn -main [& args]
  (let [repeats (if (seq args) (Long/parseLong (first args)) 5)]
    (if (pos? repeats)
      (let [pools {2 (driver/pool 2) 4 (driver/pool 4)}]
        (try
          (prn {:experiment :partitioned-range-workers :date "2026-10-13"
                :samples-per-mode repeats :warmups-per-mode 1 :cache :disabled
                :java-version (System/getProperty "java.version")
                :available-processors (.availableProcessors (Runtime/getRuntime))
                :completion :all-partitions :pool-creation :outside-timing
                :cases (mapv (fn [[kind n]] (compare-case kind n pools repeats))
                             [[:ttms 4] [:ttms 5] [:ttms 8]
                              [:domain 8] [:domain 32] [:domain 64]])})
          (finally (doseq [pool (vals pools)] (driver/close! pool)))))
      (throw (ex-info "Positive sample count required" {:samples repeats})))))
