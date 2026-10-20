(ns graph.lain-editor.experiment-comparison-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.constraint-checkpoint :as checkpoint]
            [graph.lain-editor.experiment-baseline :as baseline]
            [graph.lain-editor.experiment-comparison :as comparison]
            [graph.compiler-2-assembly :as assembly]
            [propagators.compiler-2.runtime :as runtime]))

(deftest clean-reconstruction-agrees-with-an-equivalent-incremental-ledger
  (let [client "comparison-test"
        instance {:schema "compiler-2/versioned-instance" :schema-version 1
                  :clients [{:client-id client :mode :versioned-premise}]
                  :commits [{:commit-id "40000000-0000-0000-0000-000000000001"
                             :client-id client :index 0
                             :expected-version nil :text "41"}
                            {:commit-id "40000000-0000-0000-0000-000000000002"
                             :client-id client :index 0
                             :expected-version 0 :text "42"}]}
        outcomes [{:constraint/id :answer
                   :specification/revision baseline/specification-revision
                   :status :satisfied :required? true :support #{:active}}]
        session (assembly/new-session)]
    (try
      (runtime/import-instance! session instance)
      (let [incremental (checkpoint/portable-summary
                         (checkpoint/checkpoint
                          {:id :incremental
                           :specification-revision baseline/specification-revision
                           :state @session :outcomes outcomes
                           :membership-complete? true
                           :fixture-expectations {:answer :satisfied}
                           :diagnostics [] :evidence [] :timing {}}))
            clean (:checkpoint
                   (comparison/clean-reconstruct!
                    {:instance instance
                     :specification-revision baseline/specification-revision
                     :outcomes outcomes :membership-complete? true
                     :fixture-expectations {:answer :satisfied}
                     :diagnostics [] :evidence []}))
            result (comparison/compare-checkpoints incremental clean)]
        (is (:agrees? result))
        (is (empty? (:mismatched-sections result)))
        (is (= 1 (get-in result [:clean :constraint/summary :satisfied]))))
      (finally
        (runtime/stop-clocks! session)))))
