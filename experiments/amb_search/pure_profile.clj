(ns experiments.amb-search.pure-profile
  "Isolated diagnostics for the existing builder-level p:amb benchmark."
  (:require [clojure.set :as set]
            [examples.lain.amb.builder :as builder]
            [examples.lain.amb.incremental-learning :as incremental]
            [propagators.experimental.amb.learning :as learning]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.policy :as policy]
            [propagators.experimental.runner.profiling :as profiling]
            [propagators.network-cache :as cache]
            [propagators.runner :as runner]))

(defn sample [mode]
  (let [profile (profiling/profiler)
        checked (atom [])
        reasons (atom #{})
        predicate (fn [a b c]
                    (swap! checked conj [a b c])
                    (builder/pythagorean? a b c))
        built (case mode
                :learning (builder/build 5 predicate)
                :incremental (incremental/build 5 predicate))
        convert learning/certificate->nogood
        started (System/nanoTime)
        result (binding [cache/*cache* nil]
                 (with-redefs [runner/network-iterator #(profiling/network-iterator profile)
                               learning/learn (profiling/phase profile :amb/learn learning/learn)
                               learning/closure (profiling/phase profile :amb/closure learning/closure)
                               learning/minimize (profiling/phase profile :amb/minimize learning/minimize)
                               learning/resolve-choice (profiling/phase profile :amb/resolve learning/resolve-choice)
                               policy/decide (profiling/phase profile :amb/decide policy/decide)
                               learning/certificate->nogood
                               (fn [& args]
                                 (let [nogood (apply convert args)]
                                   (when nogood (swap! reasons conj nogood))
                                   nogood))]
                   (runner/run-network (:tasks built) (:network built))))
        finished (System/nanoTime)
        network (:network result)
        known (observation/cell-base network (get-in built [:cells :knowledge]))
        solution (observation/cell-base network (get-in built [:cells :output]))
        blocked (into #{}
                      (filter (fn [tuple]
                                (learning/blocked?
                                 known
                                 (set (map vector (get-in built [:cells :candidates]) (map dec tuple)))
                                 #{})))
                      (for [a (range 1 6) b (range 1 6) c (range 1 6)] [a b c]))]
    (assert (= :completed (:status result)) (str (:error result)))
    (assert (= [3 4 5] solution))
    {:mode mode :elapsed-ms (/ (- finished started) 1e6)
     :solution solution :predicate-checks (count @checked)
     :distinct-tuples (count (set @checked))
     :blocked-complete-tuples (count blocked)
     :blocked-unchecked-tuples (count (set/difference blocked (set @checked)))
     :unique-failure-reasons (count @reasons)
     :failure-widths (frequencies (map (comp count :literals) @reasons))
     :retained-widths (frequencies (map (comp count :literals) known))
     :profile (profiling/report profile)}))

(defn -main [& _]
  (try
    ;; Warm both paths outside the reported samples, then profile sequentially.
    (doseq [mode [:learning :incremental]] (sample mode))
    (doseq [mode [:learning :incremental]] (prn (sample mode)))
    (finally (shutdown-agents))))
