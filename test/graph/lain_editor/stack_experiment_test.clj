(ns graph.lain-editor.stack-experiment-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.experiment-baseline :as baseline]
            [graph.lain-editor.stack-experiment :as experiment]))

(deftest declared-stack-fixtures-have-revision-matched-executable-outcomes
  (let [evaluation (experiment/evaluate-all)
        executions (:executions evaluation)
        outcomes (:outcomes evaluation)
        constraint-summary (:constraint/summary evaluation)
        fixture-summary (:fixture/summary evaluation)
        observed (into {} (map (juxt :constraint/id :status)) outcomes)
        by-id (into {} (map (juxt :fixture/id identity)) executions)]
    (is (= baseline/fixture-expectations observed))
    (is (= {:required-count 8
            :membership-complete? true
            :satisfied 5
            :violated 1
            :pending 1
            :contradictory 1
            :inactive-or-withdrawn 1
            :required-constraint-satisfaction 5/8}
           constraint-summary))
    (is (= 9 (:checked fixture-summary)))
    (is (= 9 (:agreements fixture-summary)))
    (is (= 1 (:fixture-expectation-agreement fixture-summary)))
    (is (= :pending (get-in by-id [:late-refinement :initial-status])))
    (is (= {:planes 3 :expansions 2 :references 0 :max-depth 1}
           (get-in by-id [:distinct-calls :scene/counts])))
    (is (= {:planes 2 :expansions 1 :references 1 :max-depth 1}
           (get-in by-id [:sibling-sharing :scene/counts])))
    (is (= {:planes 1 :expansions 0 :references 1 :max-depth 0}
           (get-in by-id [:reference-cycle :scene/counts])))
    (is (= {:planes 33 :expansions 32 :references 0 :max-depth 32}
           (get-in by-id [:deep-tail-expansion :scene/counts])))
    (is (every? #(not (contains? % :result)) executions)
        "persistable fixture evidence contains no live stack capability")))
