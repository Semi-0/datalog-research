(ns graph.lain-editor.stack.stack-live-trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.inspection.semantic-capture :as semantic-capture]
            [graph.lain-editor.stack.scene :as scene]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.semantic-trace.capture :as capture]))

(def source-path "examples/lain/top_down/stack-live-trace.lain")

(defn- binding-value [session name]
  (let [state @session
        network (:program/net state)
        id (env/resolve-binding-id network (:program/env state) name)]
    (data/payload (net/network-cell-strongest network id))))

(defn- missing-scopes [captured]
  (for [[_ graph] (capture/scopes captured)
        [parent occurrence] (:semantic-capture/nested-scopes graph)
        :when (nil? (capture/scope captured occurrence))]
    {:parent parent :scope occurrence}))

(defn- acquire-reachable! [session]
  (loop [acquired []]
    (let [captured (binding-value session 'captured)
          request (first (missing-scopes captured))]
      (if request
        (do
          (semantic-capture/publish-refinement! session captured request)
          (recur (conj acquired request)))
        acquired))))

(deftest live-example-resumes-from-real-frozen-child-scopes
  (let [pane (editor/prepare (slurp source-path) [])
        session (:session pane)]
    (try
      (let [initial (binding-value session 'captured)]
        (is (capture/fragment? initial))
        (is (seq (:semantic-capture/nested-scopes
                  (capture/scope initial :root))))
        (is (value/nothing? (binding-value session 'stacks))
            "the stack waits instead of treating an unacquired child as empty"))
      (let [requests (acquire-reachable! session)
            captured (binding-value session 'captured)
            stack (binding-value session 'stacks)
            compiled (scene/compile-scene (:program/net @session) stack)
            child-planes (filter #(= 1 (:depth %)) (:planes compiled))
            child-inputs (for [plane child-planes
                               [id label] (get-in plane [:graph :nodes])
                               :when (= 'value label)]
                           (get-in plane [:graph :values id]))]
        (testing "all refinements belong to the original observation"
          (is (seq requests))
          (is (every? #(= (capture/observation-id captured)
                          (first (:scope %)))
                      requests)))
        (testing "the resulting scene contains the pipeline and its two calls"
          (is (not (value/unusable? stack)))
          (is (= 3 (count (:planes compiled))))
          (is (= 2 (count (:edges compiled))))
          (is (= #{0 1} (set (map :depth (:planes compiled)))))
          (is (every? #(seq (get-in % [:graph :nodes])) (:planes compiled)))
          (is (= #{3 7} (set child-inputs))
              "the child planes retain their distinct stage inputs")
          (is (every? #(= #{'+ '*}
                          (set (filter #{'+ '*}
                                       (vals (get-in % [:graph :nodes])))))
                      child-planes)
              "primitive operations remain inside each compound plane")
          (is (empty? (:diagnostics compiled)))))
      (finally
        (editor/close-session! session)))))
