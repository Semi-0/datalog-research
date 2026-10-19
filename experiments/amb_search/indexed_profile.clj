(ns experiments.amb-search.indexed-profile
  "Phase timing for the dedicated runner, isolated from live editor execution."
  (:require [examples.lain.amb.builder :as builder]
            [experiments.amb-search.indexed-runner :as indexed]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.amb.learning :as learning]
            [propagators.experimental.amb.policy :as policy]
            [propagators.experimental.runner.profiling :as profiling]
            [propagators.network :as net]
            [propagators.network-cache :as cache]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(defn sample []
  (let [built (builder/build 5 builder/pythagorean?)
        profile (profiling/profiler)
        started (System/nanoTime)
        result (binding [cache/*cache* nil]
                 (with-redefs [runner/evaluate-propagator
                               (profiling/timed profile
                                                (fn [id network _] [:activation (prop/prop-name
                                                                                (net/network-lookup-propagator network id))])
                                                runner/evaluate-propagator)
                               runner/apply-patch (profiling/phase profile :patch/apply runner/apply-patch)
                               collection/merge-content (profiling/phase profile :ttms/merge collection/merge-content)
                               collection/strongest-value (profiling/phase profile :ttms/project collection/strongest-value)
                               collection/retire-superseded (profiling/phase profile :index/retire collection/retire-superseded)
                               indexed/initialize (profiling/phase profile :index/initialize indexed/initialize)
                               indexed/index-cell (profiling/phase profile :index/maintain @#'indexed/index-cell)
                               indexed/project-withdrawal (profiling/phase profile :index/withdraw @#'indexed/project-withdrawal)
                               indexed/admit (profiling/phase profile :index/admit @#'indexed/admit)
                               learning/learn (profiling/phase profile :amb/learn learning/learn)
                               policy/decide (profiling/phase profile :amb/decide policy/decide)]
                   (indexed/run built)))]
    (runner/completed-network result)
    {:elapsed-ms (/ (- (System/nanoTime) started) 1e6)
     :index-stats (:index-stats result) :profile (profiling/report profile)}))

(defn -main [& _]
  (try
    (sample)
    (prn (sample))
    (finally (shutdown-agents))))
