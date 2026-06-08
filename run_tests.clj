(ns run-tests
  "Run all project test namespaces. Usage:
  clojure -M:test                          ; default non-benchmark suites
  clojure -M:test propagators              ; stable propagator suites
  clojure -M:test propagators-network-test ; one suite"
  (:require [clojure.test :refer [run-tests]]))

(def propagators-test-namespaces
  '[propagators-bool4-test
    propagators-behavior-test
    propagators-cell-protocol-test
    propagators-compile-2-test
    propagators-compound-data-test
    propagators-compound-object-test
    propagators-dispatch-test
    propagators-generic-procedure-test
    propagators-layered-procedure-test
    propagators-linked-list-access-test
    propagators-named-network-test
    propagators-recursive-compound-test
    propagators-network-test
    propagators-structural-records-test])

(def all-test-namespaces
  '[propagators-bool4-test
    propagators-behavior-test
    propagators-cell-protocol-test
    propagators-compile-2-test
    propagators-compound-data-test
    propagators-compound-object-test
    propagators-dispatch-test
    propagators-generic-procedure-test
    propagators-layered-procedure-test
    propagators-linked-list-access-test
    propagators-named-network-test
    propagators-recursive-compound-test
    propagators-network-test
    propagators-structural-records-test
    leapfrog-pure-test
    differential-leapfrog-test
    differential-dataflow-frontier-test
    differential-dataflow-stream-ops-test
    differential-dataflow-v1-test
    differential-dataflow-graph-test
    differential-dataflow-multiset-test])

(defn- run-suite [ns-sym]
  (require ns-sym)
  (let [{:keys [pass fail error]} (run-tests ns-sym)]
    (println (str ns-sym ":") pass "pass," fail "fail," error "error")
    {:pass (or pass 0) :fail (or fail 0) :error (or error 0)}))

(defn- requested-suites [args]
  (if (seq args)
    (mapcat #(if (= "propagators" %)
               propagators-test-namespaces
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
