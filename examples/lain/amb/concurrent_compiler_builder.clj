(ns examples.lain.amb.concurrent-compiler-builder
  "Paired four-worker enumeration: opaque/decomposed builder and compiled Lain."
  (:require [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.checkpoint-enumeration :as checkpoint]
            [examples.lain.amb.checkpoint-question :as opaque]
            [examples.lain.amb.decomposed-question :as decomposed]
            [examples.lain.amb.enumeration-benchmark :as enumeration]
            [examples.lain.amb.experiment :as inspection]
            [examples.lain.amb.solver-benchmark :as solver]
            [examples.lain.amb.solver-lain :as lain]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.network-cache :as cache]))

(def modes [:builder-opaque :builder-decomposed :lain-compiled])

(defn prepare [n]
  (let [started (System/nanoTime) original (opaque/build n)
        opaque-at (System/nanoTime) matched (decomposed/build n)
        matched-at (System/nanoTime) compiled (lain/compile-source (lain/source-for n))
        compiled-at (System/nanoTime)
        state (binding [cache/*cache* nil] (lain/prepare compiled))
        finished (System/nanoTime)]
    {:bound n :opaque original :decomposed matched :compiled compiled :state state
     :setup-ms {:builder-opaque (/ (- opaque-at started) 1e6)
                :builder-decomposed (/ (- matched-at opaque-at) 1e6)
                :lain-compile (/ (- compiled-at matched-at) 1e6)
                :lain-prepare (/ (- finished compiled-at) 1e6)}
     :question-topology {:builder-opaque (inspection/topology (:network original))
                         :builder-decomposed (inspection/topology (:network matched))}
     :outer-topology (inspection/topology (:network state))}))

(defn sample [fixture mode pool]
  (case mode
    :lain-compiled (solver/sample fixture :concurrent pool)
    :builder-opaque
    (enumeration/sample #(checkpoint/run-partitions (checkpoint/template (:opaque fixture))
                                                    4 pool enumeration/profile-worker) true)
    :builder-decomposed
    (enumeration/sample #(checkpoint/run-partitions (checkpoint/template (:decomposed fixture))
                                                    4 pool enumeration/profile-worker) true)
    (throw (ex-info "Unknown concurrent comparison mode" {:mode mode}))))

(defn checked [n result]
  (let [expected (vec (sort (oracle/exhaustive n)))]
    (if (and (= :completed (:status result)) (= expected (:answers result))
             (= (* n n n) (get-in result [:metrics :leaves])))
      result
      (throw (ex-info "Concurrent comparison disagrees with exhaustive question"
                      {:bound n :expected expected :result result})))))

(defn compare-bound [n pool repeats]
  (let [fixture (prepare n)]
    (doseq [mode modes] (checked n (sample fixture mode pool)))
    (let [rows (reduce
                (fn [rows index]
                  (reduce (fn [rows mode]
                            (let [result (checked n (sample fixture mode pool))]
                              (binding [*out* *err*]
                                (prn {:bound n :mode mode :ms (:run-ms result)}))
                              (update rows mode (fnil conj []) result))) rows
                          (if (even? index) modes (reverse modes))))
                {} (range repeats))
          reports (into {} (map (fn [[mode rows]] [mode (solver/summary rows)])) rows)]
      (merge (select-keys fixture [:bound :setup-ms :question-topology :outer-topology])
             {:modes reports
              :compiled-over-decomposed (/ (get-in reports [:lain-compiled :median-ms])
                                           (get-in reports [:builder-decomposed :median-ms]))
              :compiled-over-opaque (/ (get-in reports [:lain-compiled :median-ms])
                                       (get-in reports [:builder-opaque :median-ms]))}))))

(defn -main [& arguments]
  (let [repeats (if (seq arguments) (Long/parseLong (first arguments)) 3)
        bounds (if (next arguments) (mapv #(Long/parseLong %) (rest arguments)) [4 5 8])]
    (if (and (pos? repeats) (every? pos? bounds))
      (let [pool (driver/pool 4)]
        (try
          (prn {:experiment :concurrent-compiled-vs-builder :date "2026-10-13"
                :samples repeats :warmups 1 :workers 4 :cache :disabled
                :java (System/getProperty "java.version")
                :processors (.availableProcessors (Runtime/getRuntime))
                :cases (mapv #(compare-bound % pool repeats) bounds)})
          (finally (driver/close! pool))))
      (throw (ex-info "Positive repeat count and bounds required" {})))))
