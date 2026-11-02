(ns run-tests-policy-test
  (:require [clojure.test :refer [deftest is]]
            [run-tests :as runner]))

(deftest production-retains-shared-regressions
  (let [production (set runner/production-test-namespaces)]
    (is (not-any? production runner/experimental-harness-test-namespaces))
    (doseq [ns-sym '[propagators.compiler-2-combinator-test
                    propagators.compiler-2.immutable-session-test
                    propagators.compiler-2.async-operator-builder-test
                    graph.lain-editor.runtime.external-session-test
                    graph.lain-editor.runtime.immutable-session-test
                    graph.lain-editor.inspection.high-level-trace-test
                    graph.lain-editor.inspection.high-level-value-history-test]]
      (is (contains? production ns-sym)))))

(deftest mixed-namespaces-partition-without-losing-tests
  (doseq [ns-sym runner/mixed-harness-test-namespaces]
    (require ns-sym)
    (let [production (set (runner/selected-test-vars ns-sym :production))
          harness (set (runner/selected-test-vars ns-sym :harness))
          all (set (runner/selected-test-vars ns-sym :all))]
      (is (seq production))
      (is (seq harness))
      (is (not-any? production harness))
      (is (= all (into production harness))))))
