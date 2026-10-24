(ns experiments.merge-deprecation.audit
  "Isolated JVM audit. Never install this instrumentation in a running editor."
  (:require [clojure.test :as test]
            [propagators.cell-evaluator :as evaluator]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(def default-suites
  '[graph.lain-editor.inspection.network-replay-test
    graph.lain-editor.inspection.recursive-semantic-test
    graph.lain-editor.inspection.semantic-gallery-test
    propagators.compiler-2.runtime.semantic-traversal-test])

(def ^:dynamic *destination* nil)
(def ^:dynamic *producer* nil)

(defn- category [x]
  (cond
    (value/nothing? x) :nothing
    (value/contradiction? x) :contradiction
    (map? x) {:keys (vec (sort-by pr-str (keys x)))}
    :else (str (class x))))

(defn- record! [events rule content incoming result fallback]
  (let [event {:rule rule :producer *producer* :destination *destination*
               :content (category content) :update (category incoming)
               :fallback-equivalent? (= result fallback)
               :fallback-contradiction? (value/contradiction? fallback)}]
    (swap! events update event (fnil inc 0))))

(defn audit-suite
  "Instrument all built-in handlers in this dedicated audit process.
  The fallback experiment returns default merge results only for graph values."
  [suite fallback?]
  (require suite)
  (let [events (atom {})
        methods-before (methods merge/built-in-cell-merge)
        default-method (get methods-before :default)
        evaluate-cell evaluator/evaluate-cell
        evaluate-patches runner/evaluate-patches]
    (try
      (doseq [[rule original] methods-before]
        (.addMethod merge/built-in-cell-merge rule
                    (fn [content incoming network]
                      (let [result (original content incoming network)
                            fallback (if (= rule :semantic-trace-graph)
                                       (default-method content incoming network)
                                       result)]
                        (record! events rule content incoming result fallback)
                        (if (and fallback? (= rule :semantic-trace-graph))
                          fallback
                          result)))))
      (let [result
            (with-redefs [evaluator/evaluate-cell
                          (fn [id message network]
                            (binding [*destination* (pr-str id)]
                              (evaluate-cell id message network)))
                          runner/evaluate-patches
                          (fn [evaluated network continuation]
                            (let [emitter (:emitter evaluated)
                                  producer (get (net/net-env network) (last emitter))]
                              (binding [*producer* {:emitter (pr-str emitter)
                                                    :name (pr-str (prop/prop-name producer))}]
                                (evaluate-patches evaluated network continuation))))]
              (test/run-tests suite))]
        {:suite suite :mode (if fallback? :graph-default-fallback :baseline)
         :tests result
         :counts (reduce-kv (fn [counts event n]
                              (update counts (:rule event) (fnil + 0) n)) {} @events)
         :events (mapv (fn [[event n]] (assoc event :count n))
                       (sort-by (comp pr-str key) @events))})
      (finally
        (doseq [[rule original] methods-before]
          (.addMethod merge/built-in-cell-merge rule original))))))

(defn -main [& args]
  (let [fallback? (= "--graph-default-fallback" (first args))
        names (if fallback? (rest args) args)
        suites (if (seq names) (mapv symbol names) default-suites)]
    (doseq [suite suites]
      (prn :merge-audit (audit-suite suite fallback?)))
    (shutdown-agents)))
