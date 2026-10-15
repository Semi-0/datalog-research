(ns examples.lain.amb.compiler-checkpoint-benchmark
  "Same checkpoint strategy and predicate across builder and Compiler 2."
  (:require [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.checkpoint-benchmark :as benchmark]
            [examples.lain.amb.checkpoint-question :as question]
            [examples.lain.amb.checkpoint-search :as search]
            [examples.lain.amb.compiler-checkpoint :as compiled]
            [examples.lain.amb.experiment :as inspection]
            [propagators.network-cache :as cache]))

(def modes [:builder :lain-direct :lain-application])

(defn prepare [n mode]
  (let [started (System/nanoTime)
        built (case mode
                :builder (question/build n)
                :lain-direct (compiled/build n :direct)
                :lain-application (compiled/build n :application)
                :lain-arithmetic (compiled/build n :arithmetic)
                (throw (ex-info "Unknown benchmark mode" {:mode mode})))
        built-at (System/nanoTime)
        settled (binding [cache/*cache* nil]
                  (search/settle (select-keys built [:network :tasks])))
        finished (System/nanoTime)]
    {:cold built :ready (merge built settled)
     :setup-ms (/ (- built-at started) 1e6)
     :bootstrap-ms (/ (- finished built-at) 1e6)
     :initial (inspection/topology (:network built))
     :settled (inspection/topology (:network settled))}))

(defn checked-run [n built]
  (let [run (search/run built :checkpoint)
        expected (:solution (oracle/chronological n))
        expected-status (if (some? expected) :solution :exhausted)]
    (if (= [expected-status expected] [(:status run) (:solution run)])
      (assoc run :topology (inspection/topology (get-in run [:state :network])))
      (throw (ex-info "Compiled checkpoint disagrees with oracle"
                      {:bound n :expected expected :actual (select-keys run [:status :solution])})))))

(defn measured [n mode built]
  (let [run (benchmark/sample #(checked-run n built))]
    (binding [*out* *err*] (prn {:bound n :mode mode :run-ms (:run-ms run)}))
    run))

(defn compare-bound [n samples]
  (let [prepared (into {} (map (fn [mode] [mode (prepare n mode)]) modes))]
    (doseq [mode modes] (checked-run n (get-in prepared [mode :ready])))
    (let [rows (reduce
                (fn [rows index]
                  (reduce (fn [rows mode]
                            (update rows mode conj
                                    (measured n mode (get-in prepared [mode :ready]))))
                          rows (if (even? index) modes (reverse modes))))
                (zipmap modes (repeat [])) (range samples))
          reports (into {}
                        (map (fn [mode]
                               [mode (merge (select-keys (get prepared mode)
                                                        [:setup-ms :bootstrap-ms :initial :settled])
                                            {:cold-run (benchmark/sample
                                                        #(checked-run n (get-in prepared [mode :cold])))
                                             :search (benchmark/summarize (get rows mode))
                                             :final (:topology (checked-run n (get-in prepared [mode :ready])))})]))
                        modes)
          paths (map #(select-keys (get-in reports [% :search :metrics])
                                   [:decisions :leaves :restores :max-depth]) modes)]
      (if (apply = paths)
        {:bound n :modes reports}
        (throw (ex-info "Compiler changed the DFS search path" {:bound n :paths paths}))))))

(defn -main [& arguments]
  (let [samples (if (seq arguments) (Long/parseLong (first arguments)) 3)]
    (if (pos? samples)
      (prn {:experiment :compiler-vs-builder-checkpoints :date "2026-10-13"
            :samples samples :warmups 1 :cache :disabled :threading :single
            :java (System/getProperty "java.version")
            :cases (mapv #(compare-bound % samples) [4 5 8])})
      (throw (ex-info "Positive sample count required" {:samples samples})))))
