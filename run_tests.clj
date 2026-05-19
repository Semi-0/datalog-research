(ns run-tests
  "Run all project test namespaces. Usage:
  clj -M:test                    ; all suites
  clj -M:test propagators-network-test  ; one suite"
  (:require [clojure.test :refer [run-tests]]))

(def all-test-namespaces
  '[propagators-network-test
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

(defn -main [& args]
  (let [suites (if (seq args) (map symbol args) all-test-namespaces)
        results (mapv run-suite suites)
        pass (reduce + 0 (map :pass results))
        fail (reduce + 0 (map :fail results))
        error (reduce + 0 (map :error results))]
    (println (str "\nTOTAL: " pass " pass, " fail " fail, " error " error"))
    (when (pos? (+ fail error)) (System/exit 1))))
