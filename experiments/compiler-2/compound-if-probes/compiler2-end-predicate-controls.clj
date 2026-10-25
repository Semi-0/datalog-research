;; Current predicate contracts; an unresolved tail does not prove completion.
;; Run from repository root with test and editor/src on the classpath.
(require '[clojure.test :as test]
         '[propagators.compiler-2-supported-compound-if-test :as acceptance])

(binding [test/*report-counters* (ref test/*initial-report-counters*)]
  (test/test-vars [#'acceptance/predicates-distinguish-pending-empty-and-nonempty-lists
                   #'acceptance/selected-list-shortening-replaces-the-old-tail-with-known-empty
                   #'acceptance/selected-explicit-open-tail-stays-pending-until-it-is-closed])
  (let [{:keys [pass fail error] :as result} @test/*report-counters*]
    (prn result)
    (shutdown-agents)
    (System/exit (if (and (pos? (+ pass fail error)) (zero? (+ fail error))) 0 1))))
