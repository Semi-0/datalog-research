(ns experiments.amb-search.indexed-benchmark
  (:require [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.builder :as builder]
            [examples.lain.amb.checkpoint-benchmark :as measurement]
            [experiments.amb-search.concurrent-benchmark :as inspection]
            [experiments.amb-search.indexed-runner :as indexed]
            [propagators.network-cache :as cache]
            [propagators.runner :as runner]))

(defn sample [built mode]
  (let [calls (atom 0)
        activate runner/evaluate-propagator
        before (measurement/allocated-bytes)
        started (System/nanoTime)
        result (binding [cache/*cache* nil]
                 (with-redefs [runner/evaluate-propagator
                               (fn [id network handlers]
                                 (swap! calls inc)
                                 (activate id network handlers))]
                   (case mode
                     :ordinary (runner/run-network (:tasks built) (:network built))
                     :indexed (indexed/run built))))
        finished (System/nanoTime)
        after (measurement/allocated-bytes)
        network (runner/completed-network result)
        sig (inspection/signature built network)]
    {:status :completed :solution (:solution sig) :signature sig
     :run-ms (/ (- finished started) 1e6)
     :allocated-bytes (when (and before after) (- after before))
     :metrics (merge {:activations @calls} (:index-stats result))
     :index (when (= :indexed mode) (indexed/index-summary network))}))

(defn compare-bound [n repeats]
  (let [built (builder/build n builder/pythagorean?)
        reference (sample built :ordinary)
        _ (assert (= (:solution (oracle/chronological n)) (:solution reference)))
        checked (fn [mode]
                  (let [row (sample built mode)]
                    (assert (= (:signature reference) (:signature row)) (str "Indexed AMB parity failed: " n))
                    (dissoc row :signature)))
        _ (checked :indexed)
        rows (reduce (fn [rows i]
                       (reduce (fn [rows mode]
                                 (let [row (checked mode)]
                                   (binding [*out* *err*]
                                     (prn {:domain n :mode mode :ms (:run-ms row) :metrics (:metrics row)}))
                                   (update rows mode (fnil conj []) row)))
                               rows (if (even? i) [:ordinary :indexed] [:indexed :ordinary])))
                     {} (range repeats))]
    {:domain n :modes (into {} (map (fn [[mode samples]] [mode (measurement/summarize samples)])) rows)}))

(defn -main [& args]
  (let [repeats (if (seq args) (Long/parseLong (first args)) 5)
        bounds (if (next args) (mapv #(Long/parseLong %) (rest args)) [4 5 8])]
    (try
      (assert (pos? repeats))
      (prn {:date "2026-10-19" :java (System/getProperty "java.version")
            :cache :disabled :warmups 1 :samples repeats :initialization :included
            :cases (mapv #(compare-bound % repeats) bounds)})
      (finally (shutdown-agents)))))
