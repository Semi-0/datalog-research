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


(deftest filtered-execution-honors-selection-and-fixtures
  (let [ns-sym 'run-tests-policy-fixture
        ns-obj (create-ns ns-sym)
        seen (atom [])
        fixture (fn [f] (swap! seen conj :fixture-before) (f) (swap! seen conj :fixture-after))]
    (try
      (alter-meta! ns-obj assoc :clojure.test/once-fixtures [fixture]
                   :clojure.test/each-fixtures [fixture])
      (doseq [[name marker experimental?] [['production-case :production false]
                                           ['harness-case :harness true]]]
        (intern ns-obj (with-meta name {:test (fn [] (swap! seen conj marker) (is true))
                                      :experimental-harness experimental?}) (fn [])))
      (doseq [[mode expected] [[:production :production] [:harness :harness]]]
        (reset! seen [])
        (let [result (binding [clojure.test/*test-out* (java.io.StringWriter.)]
                       (runner/run-selected-tests ns-sym mode))]
          (is (= 1 (:test result)))
          (is (= 1 (:pass result)))
          (is (zero? (+ (:fail result) (:error result))))
          (is (= [:fixture-before :fixture-before expected :fixture-after :fixture-after] @seen))))
      (finally (remove-ns ns-sym)))))
