(ns experiments.amb-search.profile
  "Isolated synchronous diagnostic. Run with clojure -M -m experiments.amb-search.profile."
  (:require [clojure.string :as str]
            [graph.lain-editor.runtime :as editor]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.experimental.amb.learning :as learning]
            [propagators.experimental.amb.observation :as amb-observation]
            [propagators.experimental.amb.policy :as policy]
            [propagators.experimental.runner.profiling :as profiling]
            [propagators.runner :as runner]
            [propagators.relationship-observer :as relationships]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.merge :as merge]
            [propagators.semantic-trace.snapshot :as snapshot]))

(defn sample [mode]
  (let [profile (profiling/profiler)
        editors (atom {})
        original (slurp "examples/lain/amb/editor/pythagorean.lain")
        source (case mode
                 :replay original
                 :plain (str/replace original
                                     "(display (collect-semantic-history valid 0 600000))"
                                     "(display valid)"))
        started (System/nanoTime)]
    ;; Redefinitions are process-global: never run this in the live editor JVM.
    (try
      (with-redefs [runner/network-iterator #(profiling/network-iterator profile)
                    learning/learn (profiling/phase profile :amb/learn learning/learn)
                    policy/decide (profiling/phase profile :amb/decide policy/decide)
                    observation/frozen-snapshot (profiling/phase profile :capture/freeze observation/frozen-snapshot)
                    snapshot/root-graph (profiling/phase profile :capture/root-graph snapshot/root-graph)
                    relationships/snapshot (profiling/phase profile :capture/nodes relationships/snapshot)
                    relationships/connected-root-node-keys
                    (let [select relationships/connected-root-node-keys]
                      (fn [& args]
                        (profiling/phase profile :capture/select (apply select args))))
                    relationships/lexical-binding-names
                    (profiling/phase profile :capture/labels @#'relationships/lexical-binding-names)
                    capture/join (profiling/phase profile :capture/join capture/join)
                    merge/joined-groups (profiling/phase profile :capture/groups merge/joined-groups)]
        ((profiling/phase profile :editor/load editor/run!)
         editors {:pane "profile" :source source})
        ((profiling/phase profile :editor/extend editor/extend!)
         editors {:pane "profile" :generation (:generation (get @editors "profile"))
                  :source "(-> (list 3 4 5) domain)"})
        (let [session @(:session (get @editors "profile"))
              value-at #(amb-observation/cell-base
                         (:program/net session)
                         (env/resolve-binding-id (:program/net session) (:program/env session) %))
              solution (mapv value-at '[a b c])]
          (assert (= [3 4 5] solution))
          {:mode mode :elapsed-ms (/ (- (System/nanoTime) started) 1e6)
           :solution solution :profile (profiling/report profile)}))
      (finally (editor/close! editors)))))

(defn -main [& modes]
  (try
    (doseq [mode (if (seq modes) (map keyword modes) [:plain :replay])]
      (prn (sample mode))
      (flush))
    (finally (shutdown-agents))))
