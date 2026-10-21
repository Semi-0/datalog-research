(ns graph.lain-editor.stack-incremental-integration-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.development-session :as development-session]
            [graph.lain-editor.experiment-baseline :as baseline]
            [graph.lain-editor.experiment-comparison :as comparison]
            [graph.lain-editor.semantic-capture :as semantic-capture]
            [graph.lain-editor.stack-scene :as scene]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.tui.block-model :as block-model]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.semantic-trace.capture :as capture]))

(defn- semantic-result [state index]
  (let [display-id (:display-id
                    (block-model/block-by-index state baseline/client-id (inc index)))]
    (data/payload
     (net/network-cell-strongest (:program/net state) display-id))))

(defn- stack-capture [session]
  (->> @(semantic-capture/storage session)
       vals
       (map :capture)
       (filter capture/fragment?)
       (filter #(seq (:semantic-capture/nested-scopes
                      (capture/scope % :root))))
       first))

(defn- publish-root-scope! [session known]
  (let [[parent occurrence]
        (first (:semantic-capture/nested-scopes
                (capture/scope known :root)))]
    {:occurrence occurrence
     :capture (semantic-capture/publish-refinement!
               session known {:parent parent :scope occurrence})}))

(deftest versioned-wrapper-composition-resumes-after-frozen-scope-refinement
  (let [pending (baseline/pending-implementation-forms)
        concrete (baseline/implementation-forms)
        session (development-session/new-session)]
    (try
      (is (= (count pending) (count concrete)))
      (is (= (mapv second pending) (mapv second concrete))
          "each concrete definition replaces the corresponding stable name")
      (is (= {:status :imported
              :commit-count (count (get-in (baseline/refined-instance) [:commits]))}
             (select-keys (runtime/import-instance! session (baseline/refined-instance))
                          [:status :commit-count])))
      (is (value/nothing?
           (semantic-result @session (baseline/stack-query-index)))
          "an unacquired child scope remains pending")
      (let [known (stack-capture session)
            {:keys [capture occurrence]} (publish-root-scope! session known)
            result (semantic-result @session (baseline/stack-query-index))
            compiled (scene/compile-scene result)]
        (is (capture/fragment? known))
        (is (some? (capture/scope capture occurrence)))
        (is (not (value/unusable? result)))
        (is (= 1 (count (:planes compiled))))
        (is (empty? (:diagnostics compiled)))
        (let [clean (development-session/new-session)]
          (try
            (runtime/import-instance! clean (baseline/refined-instance))
            (publish-root-scope! clean (stack-capture clean))
            (let [clean-result (semantic-result @clean (baseline/stack-query-index))]
              (is (= (comparison/normalize-stack-scene compiled)
                     (comparison/normalize-stack-scene
                      (scene/compile-scene clean-result)))
                  "incremental replay and clean reconstruction agree semantically"))
            (finally
              (runtime/stop-clocks! clean))))
        (runtime/commit-version!
         session
         {:commit-id "40000000-0000-0000-0000-000000000001"
          :client-id baseline/client-id :index 5 :expected-version 1
          :text (pr-str (nth (baseline/pending-implementation-forms) 5))})
        (is (value/nothing?
             (semantic-result @session (baseline/stack-query-index)))
            "withdrawing a frozen reader withdraws the dependent stack")
        (runtime/commit-version!
         session
         {:commit-id "40000000-0000-0000-0000-000000000002"
          :client-id baseline/client-id :index 5 :expected-version 2
          :text (pr-str (nth (baseline/implementation-forms) 5))})
        (is (not (value/unusable?
                  (semantic-result @session (baseline/stack-query-index))))
            "restoring the reader resumes the retained composition"))
      (finally
        (runtime/stop-clocks! session)))))
