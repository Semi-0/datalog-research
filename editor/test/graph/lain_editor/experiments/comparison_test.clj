(ns graph.lain-editor.experiments.comparison-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.stack.checkpoint :as checkpoint]
            [graph.lain-editor.experiments.baseline :as baseline]
            [graph.lain-editor.experiments.comparison :as comparison]
            [graph.compiler-2-assembly :as assembly]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.ids :as ids]))

(defn- semantic-graph [left right label]
  {:nodes {left label right "result"}
   :node-kinds {left :compound right :primitive}
   :values {right 42}
   :edges #{[left right]}})

(deftest semantic-scene-normalization-ignores-generated-identities
  (let [a1 [[:scope] (ids/new-node-id)]
        b1 [[:scope] (ids/new-node-id)]
        a2 [[:scope] (ids/new-node-id)]
        b2 [[:scope] (ids/new-node-id)]
        graph-1 (semantic-graph a1 b1 "call")
        graph-2 (semantic-graph a2 b2 "call")
        scene-1 {:stackScene true :root :runtime-root-1
                 :planes [{:identity :runtime-root-1 :depth 0 :order 0
                           :graph graph-1}
                          {:identity :runtime-child-1 :depth 1 :order 1
                           :graph (semantic-graph a1 b1 "child")}]
                 :edges [{:from :runtime-root-1 :source-node a1
                          :to :runtime-child-1 :kind :expansion}]
                 :diagnostics []}
        scene-2 {:stackScene true :root :runtime-root-2
                 :planes [{:identity :runtime-root-2 :depth 0 :order 0
                           :graph graph-2}
                          {:identity :runtime-child-2 :depth 1 :order 1
                           :graph (semantic-graph a2 b2 "child")}]
                 :edges [{:from :runtime-root-2 :source-node a2
                          :to :runtime-child-2 :kind :expansion}]
                 :diagnostics []}]
    (is (= (comparison/normalize-semantic-graph graph-1)
           (comparison/normalize-semantic-graph graph-2)))
    (is (= (comparison/normalize-stack-scene scene-1)
           (comparison/normalize-stack-scene scene-2)))
    (is (not= (comparison/normalize-semantic-graph graph-1)
              (comparison/normalize-semantic-graph
               (assoc graph-2 :edges #{[b2 a2]})))
        "edge direction remains semantic")
    (is (not= (comparison/normalize-semantic-graph graph-1)
              (comparison/normalize-semantic-graph
               (assoc-in graph-2 [:values b2] 43)))
        "node values remain semantic")))

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
