(ns examples.lain.amb.solver-benchmark
  "Compile once; select a solver cell in the same prepared environment."
  (:require [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.brute-force :as brute]
            [examples.lain.amb.checkpoint-benchmark :as measurement]
            [examples.lain.amb.experiment :as inspection]
            [examples.lain.amb.parallel-benchmark :as profiling]
            [examples.lain.amb.solver-lain :as lain]
            [examples.lain.amb.solver-operator :as operator]
            [examples.lain.amb.solver-strategy :as strategy]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.network-cache :as cache]))

(def modes [:repair :checkpoint :partitioned-sequential :concurrent])
(def limits {:ms 60000 :activations 200000})

(defn prepare [n]
  (let [started (System/nanoTime) compiled (lain/compile-source (lain/source-for n))
        compiled-at (System/nanoTime)
        state (binding [cache/*cache* nil] (lain/prepare compiled))
        prepared-at (System/nanoTime) baseline (brute/compile-bound n)
        finished (System/nanoTime)]
    {:compiled compiled :state state :baseline baseline :bound n
     :compile-ms (/ (- compiled-at started) 1e6)
     :prepare-ms (/ (- prepared-at compiled-at) 1e6)
     :baseline-compile-ms (/ (- finished prepared-at) 1e6)
     :outer-topology (inspection/topology (:network state))}))

(defn sample [fixture choice executor]
  (let [events (atom [])
        result (profiling/profile
                #(binding [strategy/*executor* executor operator/*observe-solve* (fn [v] (swap! events conj v))]
                   (if (= :brute-force choice)
                     (brute/execute (:baseline fixture) limits)
                     (lain/select-and-run (:compiled fixture) (get-in fixture [:state :network]) choice))))
        parent (:profile result) solver-result (:result (peek @events))
        workers (mapv :profile (:branches solver-result))
        allocations (map :allocated-bytes (if (= :concurrent choice) (cons parent workers) [parent]))
        fronts (if (seq workers) (map :frontier (:branches solver-result)) [(:frontier solver-result)])]
    {:status (:status result) :answers (:answers result) :diagnostic (:diagnostic result)
     :run-ms (:run-ms parent)
     :allocated-bytes (if (every? some? allocations) (reduce + allocations) nil)
     :topology (if (:network result) (inspection/topology (:network result)) nil)
     :metrics {:activations (reduce + (:activations (:metrics parent))
                                   (map #(get-in % [:metrics :activations]) workers))
               :peak-env (apply max (get-in parent [:metrics :peak-env] 0)
                                (map #(get-in % [:metrics :peak-env] 0) workers))
               :leaves (if (every? some? fronts) (reduce + (map :leaves fronts)) nil)
               :logical-nogoods (if (:logical-nogoods solver-result) (count (:logical-nogoods solver-result)) nil)
               :answer-exclusions (if (:answer-exclusions solver-result) (count (:answer-exclusions solver-result)) nil)
               :execution-threads (count (set (map :thread (if (seq workers) workers [parent]))))}
     :solver-calls (count @events)}))

(defn checked [expected row]
  (if (and (= :completed (:status row)) (= expected (:answers row)))
    row
    (throw (ex-info "Swappable solver disagrees with exhaustive oracle"
                    {:expected expected :result row}))))

(defn summary [rows]
  (assoc (dissoc (measurement/summarize rows) :solution) :answers (:answers (first rows))
         :topology (:topology (first rows))))

(defn compare-bound [n pool repeats]
  (let [fixture (prepare n) expected (vec (sort (oracle/exhaustive n)))
        _ (doseq [mode modes] (checked expected (sample fixture mode pool)))
        brute-warmup (sample fixture :brute-force pool)
        selected (case (:status brute-warmup)
                   :completed (do (checked expected brute-warmup) (conj modes :brute-force))
                   :bounded modes
                   (throw (ex-info "Unexpected baseline status" {:result brute-warmup})))
        rows (reduce
              (fn [rows index]
                (reduce (fn [rows mode]
                          (let [row (checked expected (sample fixture mode pool))]
                            (binding [*out* *err*]
                              (prn {:bound n :mode mode :answers (count (:answers row)) :ms (:run-ms row)}))
                            (update rows mode (fnil conj []) row))) rows
                        (if (even? index) selected (reverse selected))))
              {} (range repeats))
        work (map #(select-keys (:metrics %) [:activations :leaves])
                  (concat (:partitioned-sequential rows) (:concurrent rows)))]
    (if (apply = work)
      (merge (select-keys fixture [:bound :compile-ms :prepare-ms :baseline-compile-ms :outer-topology])
             {:modes (into {} (map (fn [[mode rows]] [mode (summary rows)])) rows)
              :baseline-warmup brute-warmup})
      (throw (ex-info "Concurrent and sequential partitions did different work" {:bound n})))))

(defn -main [& arguments]
  (let [repeats (if (seq arguments) (Long/parseLong (first arguments)) 3)
        bounds (if (next arguments) (mapv #(Long/parseLong %) (rest arguments)) [2 4 5])]
    (if (and (pos? repeats) (every? pos? bounds))
      (let [pool (driver/pool 4)]
        (try
          (prn {:experiment :lain-swappable-constraint-solvers :date "2026-10-13"
                :samples repeats :warmups 1 :cache :disabled :workers 4
                :same-solver-source true :compile-once true :baseline-limits limits
                :baseline-source :separate-lain-enumeration :baseline-scalar-environment :plain
                :java (System/getProperty "java.version")
                :processors (.availableProcessors (Runtime/getRuntime))
                :cases (mapv #(compare-bound % pool repeats) bounds)})
          (finally (driver/close! pool))))
      (throw (ex-info "Positive repeat count and bounds required" {})))))
