(ns run-tests
  "Run all project test namespaces. Usage:
  clojure -M:test                          ; default non-benchmark suites
  clojure -M:test graph                    ; graph/vijual suites
  clojure -M:test differential-dataflow    ; differential dataflow suites
  clojure -M:test leapfrog                 ; leapfrog suites
  clojure -M:test propagators              ; stable propagator suites
  clojure -M:test propagators.network-test ; one suite"
  (:require [clojure.string :as str]
            [clojure.test :refer [run-tests]]))

(def propagators-test-namespaces
  '[propagators.bool4-test
    propagators.behavior-algebra-test
    propagators.behavior-arithmetic-test
    propagators.behavior-compiler-test
    propagators.behavior-test
    propagators.cell-protocol-test
    propagators.compile-2-test
    propagators.compiler-2-call-graph-test
    propagators.compiler-2-block-premise-test
    propagators.compiler-2-composition-test
    propagators.compiler-2-cps-test
    propagators.compiler-2-organization-test
    propagators.compound-data-test
    propagators.compound-object-network-slot-test
    propagators.compound-object-test
    propagators.debug-test
    propagators.dispatch-test
    propagators.generic-procedure-test
    propagators.gur-accumulating-test
    propagators.gur-subenv-test
    propagators.install-test
    propagators.layered-procedure-test
    propagators.linked-list-access-test
    propagators.named-network-test
    propagators.network-vm-flat-test
    propagators.network-vm-test
    propagators.network-vm-nested-test
    propagators.primitive-basis-test
    propagators.reducer-cell-test
    propagators.recursive-compound-test
    propagators.network-test
    propagators.structural-records-test
    propagators.tms-test])

(def graph-test-namespaces
  '[graph.vijual.math-test
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
               leapfrog-test-namespaces
               differential-dataflow-test-namespaces
               graph-test-namespaces)))

(def suite-aliases
  {"propagators" propagators-test-namespaces
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

(defn- run-suite [ns-sym]
  (require ns-sym)
  (let [{:keys [pass fail error]} (run-tests ns-sym)]
    (println (str ns-sym ":") pass "pass," fail "fail," error "error")
    {:pass (or pass 0) :fail (or fail 0) :error (or error 0)}))

(defn- requested-suites [args]
  (if (seq args)
    (mapcat #(or (suite-aliases %)
                 (legacy-propagators-suite %)
                 [(symbol %)])
            args)
    all-test-namespaces))

(defn -main [& args]
  (let [suites (requested-suites args)
        results (mapv run-suite suites)
        pass (reduce + 0 (map :pass results))
        fail (reduce + 0 (map :fail results))
        error (reduce + 0 (map :error results))]
    (println (str "\nTOTAL: " pass " pass, " fail " fail, " error " error"))
    (when (pos? (+ fail error)) (System/exit 1))))
