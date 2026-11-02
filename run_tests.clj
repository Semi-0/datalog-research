(ns run-tests
  "Run all project test namespaces. Usage:
  clojure -M:test                          ; production suites (experimental harness opt-in)
  clojure -M:test graph                    ; graph/vijual suites
  clojure -M:test differential-dataflow    ; differential dataflow suites
  clojure -M:test leapfrog                 ; leapfrog suites
  clojure -M:test propagators              ; stable propagator suites
  clojure -M:test rule-fixedpoint          ; opt-in DB/rule evaluator experiment
  clojure -M:test ttms-compound            ; opt-in compound/history experiment
  clojure -M:test network-replay           ; compiled Lain recorder/reducer and playback
  clojure -M:test propagators.network-test ; one suite"
  (:require [clojure.string :as str]
            [clojure.test :refer [run-tests]]))

(def propagators-test-namespaces
  '[run-tests-policy-test
    propagators.bool4-test
    propagators.error-layer-test
    propagators.cell-protocol-test
    propagators.combinator-test
    propagators.compile-2-test
    propagators.compiler-2-application-runtime-test
    propagators.compiler-2.immutable-session-test
    propagators.compiler-2.async-operator-builder-test
    propagators.compiler-2-call-graph-test
    propagators.compiler-2-block-premise-test
    propagators.compiler-2-composition-test
    propagators.compiler-2-cps-test
    propagators.compiler-2-conditional-syntax-test
    propagators.compiler-2-lazy-conditional-test
    propagators.compiler-2-empty-list-test
    propagators.compiler-2-open-apply-test
    propagators.compiler-2-functional-network-test
    propagators.compiler-2-supported-compound-if-test
    propagators.compiler-2-supported-publication-test
    propagators.supported-slot-test
    propagators.compiler-2-functional-network-gur-test
    propagators.compiler-2-gur-tms-integration-test
    propagators.compiler-2-rest-parameters-test
    propagators.compiler-2-combinator-test
    propagators.compiler-2-subagent-harness-test
    propagators.compiler-2-harness-primitives-test
    propagators.compiler-2-harbor-extension-test
    propagators.compiler-2-harness-effect-boundary-test
    propagators.compiler-2-socket-extension-test
    propagators.compiler-2-pi-extension-test
    propagators.compiler-2-harness-session-test
    propagators.how-to-think-in-lain-examples-test
    propagators.compiler-2-network-observation-test
    propagators.compiler-2.runtime.network-observation-test
    propagators.compiler-2.runtime.semantic-traversal-test
    propagators.compiler-2.runtime.retained-network-test
    propagators.observer-test
    propagators.compiler-2-organization-test
    propagators.compound-object-network-slot-test
    propagators.debug-test
    propagators.dispatch-test
    propagators.experimental.runner-constructor-test
    propagators.experimental.ttms-view-test
    propagators.experimental.tracer-topology-test
    propagators.experimental.compound-tracer-test
    propagators.experimental.compound-levels-demo-test
    propagators.experimental.functional-web-migration-test
    propagators.dataflow-projection-test
    propagators.relationship-snapshot-test
    propagators.experimental.view-xr-test
    propagators.install-test
    propagators.layered-procedure-test
    propagators.layered-dispatcher-test
    propagators.layered-arity-test
    propagators.layered-support-test
    propagators.membership-collection-test
    propagators.message-lift-test
    propagators.named-network-test
    propagators.network-patch-test
    propagators.premise-transport-test
    propagators.observation-transport-test
    propagators.reducer-cell-test
    propagators.runner-test
    propagators.semantic-trace.graph-facts-test
    propagators.semantic-trace.history-facts-test
    propagators.semantic-trace.capture-test
    propagators.semantic-trace.graph-union-test
    propagators.semantic-trace.high-level-test
    propagators.source-publication-test
    propagators.compiler-2-tms-bootstrap-test
    propagators.structural-records-test
    propagators.support-test
    propagators.support-collection-test
    propagators.support-transport-test
    propagators.support-glitch-test
    propagators.sync-contradiction-test
    propagators.ttms-branching-test
    propagators.ttms-compound-boundary-test
    propagators.ttms-primitives-test
    propagators.ttms-publication-test
    propagators.tms-test])

(def graph-test-namespaces
  '[graph.lain-editor.inspection.composable-tracer-test
    graph.lain-editor.runtime.harness-observer-test
    graph.lain-editor.inspection.high-level-trace-test
    graph.lain-editor.inspection.high-level-value-history-test
    graph.lain-editor.inspection.harness-assignment-withdrawal-test
    graph.lain-editor.inspection.retained-relationship-test
    graph.lain-editor.workspace.source-files-test
    graph.lain-editor.workspace.commands-test
    graph.lain-editor.runtime.slot-spawn-test
    graph.lain-editor.runtime.extension-error-test
    graph.lain-editor.runtime.detached-session-test
    graph.lain-editor.runtime.external-session-test
    graph.lain-editor.runtime.immutable-session-test
    graph.lain-editor.inspection.tracer-directions-test
    graph.lain-editor.inspection.semantic-scene-test
    graph.lain-editor.inspection.semantic-gallery-test
    graph.lain-editor.inspection.recursive-semantic-test
    graph.lain-editor.inspection.amb-test
    graph.lain-editor.layout.graph-layout-test
    graph.ttms-view-live-test
    graph.vijual.math-test
    propagators.compiler-2.runtime.tui.version-history-test
    propagators.compiler-2.runtime.tui.block-compiler-test
    propagators.compiler-2.runtime.tui.versioned-commit-test
    propagators.compiler-2.runtime.environment-io-test
    propagators.compiler-2.runtime.clock-test
    propagators.compiler-2.runtime.retraction-inspection-test
    propagators.compiler-2.runtime.session.instance-replay-test
    graph.compiler-2-versioned-tui.editor-test
    graph.compiler-2-versioned-tui.layout-test
    graph.vijual.scan-test
    graph.vijual.layout-test
    propagators.compiler-2.runtime.boundary-test
    graph.vijual.compiler-2-demo-test
    graph.vijual.render-test])

(def tracer-test-namespaces
  '[propagators.semantic-trace.graph-facts-test
    propagators.semantic-trace.history-facts-test
    propagators.semantic-trace.capture-test
    propagators.semantic-trace.graph-union-test
    propagators.semantic-trace.high-level-test
    propagators.support-test
    propagators.support-collection-test
    propagators.compiler-2-network-observation-test
    propagators.compiler-2.runtime.network-observation-test
    propagators.compiler-2.runtime.semantic-traversal-test
    propagators.compiler-2.runtime.retained-network-test
    propagators.compiler-2-call-graph-test
    propagators.dataflow-projection-test
    propagators.relationship-snapshot-test
    propagators.compiler-2.runtime.boundary-test
    propagators.compiler-2.runtime.integration.boundary-test
    propagators.compiler-2.runtime.trace-cycle-test
    graph.lain-editor.inspection.composable-tracer-test
    graph.lain-editor.inspection.high-level-trace-test
    graph.lain-editor.inspection.high-level-value-history-test
    graph.lain-editor.inspection.retained-relationship-test
    graph.lain-editor.inspection.tracer-directions-test
    graph.lain-editor.inspection.semantic-scene-test
    graph.lain-editor.inspection.recursive-semantic-test
    graph.lain-editor.inspection.network-replay-test
    graph.lain-editor.inspection.tracer-gallery-test
    graph.lain-editor.runtime.harness-observer-test
    propagators.experimental.ttms-view-test
    propagators.experimental.tracer-topology-test
    propagators.experimental.compound-tracer-test
    propagators.experimental.compound-levels-demo-test
    propagators.experimental.view-xr-test
    experiments.synth-test
    graph.lain-editor.inspection.semantic-gallery-test
    propagators.compiler-2-subagent-harness-test
    propagators.compiler-2-harness-session-test
    propagators.compiler-2-socket-extension-test
    graph.lain-editor.inspection.harness-assignment-withdrawal-test])

(def recovered-slice-test-namespaces
  '[graph.xr-inspection-test
    graph.xr-context-stream-test
    experiments.synth-test
    propagators.experimental.runner.profiling-test])

(def relational-experiment-test-namespaces
  '[examples.lain.relational-datalog.experiment
    examples.lain.relational-datalog.semi-naive-test
    examples.lain.relational-datalog.compare-engines-test
    examples.lain.relational-datalog.proof-experiment-test
    examples.lain.relational-datalog.clause-terms-test])

(def rule-fixedpoint-test-namespaces
  '[propagators.experimental.rule-fixedpoint-test
    propagators.experimental.rule-fixedpoint-validation-test
    propagators.experimental.rule-fixedpoint-catalog-test])

(def ttms-compound-test-namespaces
  '[propagators.experimental.ttms-compound-test
    propagators.experimental.ttms-reducer-test])

(def network-replay-test-namespaces
  '[graph.lain-editor.inspection.network-replay-test])

(def leapfrog-test-namespaces
  '[leapfrog.pure-test
    leapfrog.differential-test])

(def differential-dataflow-test-namespaces
  '[differential-dataflow.frontier-test
    differential-dataflow.stream-ops-test
    differential-dataflow.v1-test
    differential-dataflow.graph-test
    differential-dataflow.multiset-test])

(def all-test-namespaces
  (vec (concat propagators-test-namespaces
               recovered-slice-test-namespaces
               leapfrog-test-namespaces
               differential-dataflow-test-namespaces
               graph-test-namespaces)))

(def experimental-harness-test-namespaces
  '[propagators.compiler-2-subagent-harness-test
    propagators.compiler-2-harness-primitives-test
    propagators.compiler-2-harbor-extension-test
    propagators.compiler-2-harness-effect-boundary-test
    propagators.compiler-2-socket-extension-test
    propagators.compiler-2-pi-extension-test
    propagators.compiler-2-harness-session-test
    propagators.compiler-2-pi-transport-test
    propagators.compiler-2-harbor-grading-contract-test
    graph.lain-editor.runtime.harness-observer-test
    graph.lain-editor.inspection.harness-assignment-withdrawal-test])

(def mixed-harness-test-namespaces
  '[propagators.compiler-2-combinator-test
    graph.lain-editor.inspection.high-level-trace-test
    graph.lain-editor.inspection.high-level-value-history-test])

(def production-test-namespaces
  (vec (remove (set experimental-harness-test-namespaces) all-test-namespaces)))

(def compiler-session-test-namespaces
  '[propagators.compiler-2.immutable-session-test
    propagators.compiler-2.async-operator-builder-test
    graph.lain-editor.runtime.immutable-session-test
    propagators.compiler-2.runtime.session-extension-test
    propagators.compiler-2-functional-network-gur-test
    propagators.compiler-2-gur-tms-integration-test
    propagators.compiler-2-supported-compound-if-test
    propagators.semantic-trace.high-level-test
    graph.lain-editor.runtime.detached-session-test
    graph.lain-editor.inspection.high-level-trace-test])

(def suite-aliases
  {"production" production-test-namespaces
   "all" (vec (distinct (concat all-test-namespaces experimental-harness-test-namespaces)))
   "subagent-harness" (vec (concat experimental-harness-test-namespaces mixed-harness-test-namespaces))
   "tracers" tracer-test-namespaces
   "compiler-session" compiler-session-test-namespaces
   "propagators" propagators-test-namespaces
   "recovered-slices" recovered-slice-test-namespaces
   "relational-experiments" relational-experiment-test-namespaces
   "rule-fixedpoint" rule-fixedpoint-test-namespaces
   "ttms-compound" ttms-compound-test-namespaces
   "network-replay" network-replay-test-namespaces
   "graph" graph-test-namespaces
   "leapfrog" leapfrog-test-namespaces
   "differential-dataflow" differential-dataflow-test-namespaces
   "bench-compare-test" '[differential-dataflow.bench-compare-test]
   "differential-dataflow-frontier-test" '[differential-dataflow.frontier-test]
   "differential-dataflow-stream-ops-test" '[differential-dataflow.stream-ops-test]
   "differential-dataflow-v1-test" '[differential-dataflow.v1-test]
   "differential-dataflow-graph-test" '[differential-dataflow.graph-test]
   "differential-dataflow-multiset-test" '[differential-dataflow.multiset-test]
   "differential-leapfrog-test" '[leapfrog.differential-test]
   "leapfrog-pure-test" '[leapfrog.pure-test]})

(defn- legacy-propagators-suite [arg]
  (if (str/starts-with? arg "propagators-")
    [(symbol (str "propagators."
                  (subs arg (count "propagators-"))))]
    nil))

(defn selected-test-vars [ns-sym mode]
  (filter (fn [v]
            (and (:test (meta v))
                 (case mode
                   :production (not (:experimental-harness (meta v)))
                   :harness (or ((set experimental-harness-test-namespaces) ns-sym)
                                (:experimental-harness (meta v)))
                   true)))
          (vals (ns-interns ns-sym))))

(defn- run-suite [ns-sym mode]
  (require ns-sym)
  (let [{:keys [pass fail error]}
        (with-redefs [clojure.test/test-all-vars
                      (fn [ns] (clojure.test/test-vars (selected-test-vars (ns-name ns) mode)))]
          (run-tests ns-sym))]
    (println (str ns-sym ":") pass "pass," fail "fail," error "error")
    (when (zero? (+ (or pass 0) (or fail 0) (or error 0)))
      (throw (ex-info "Test suite ran zero assertions" {:namespace ns-sym})))
    {:pass (or pass 0) :fail (or fail 0) :error (or error 0)}))

(defn- requested-suites [args]
  (if (seq args)
    (mapcat #(or (suite-aliases %)
                 (legacy-propagators-suite %)
                 [(symbol %)])
            args)
    production-test-namespaces))

(defn -main [& args]
  (let [mode (cond (or (empty? args) (= ["production"] (vec args))) :production
                   (= ["subagent-harness"] (vec args)) :harness
                   :else :all)
        _ (when (and (some #{"production" "subagent-harness"} args) (> (count args) 1))
            (throw (ex-info "Run production or subagent-harness separately; use all for complete coverage" {:args args})))
        suites (distinct (requested-suites args))
        results (mapv #(run-suite % mode) suites)
        pass (reduce + 0 (map :pass results))
        fail (reduce + 0 (map :fail results))
        error (reduce + 0 (map :error results))]
    (println (str "\nTOTAL: " pass " pass, " fail " fail, " error " error"))
    (when (pos? (+ fail error)) (System/exit 1))))
