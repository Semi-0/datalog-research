(ns graph.lain-editor.stack-incremental-integration-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.development-session :as development-session]
            [graph.lain-editor.experiment-baseline :as baseline]
            [graph.lain-editor.stack-scene :as scene]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.tui.block-model :as block-model]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]))

(defn- semantic-result [state index]
  (let [display-id (:display-id
                    (block-model/block-by-index state baseline/client-id (inc index)))]
    (data/payload
     (net/network-cell-strongest (:program/net state) display-id))))

(deftest versioned-wrapper-composition-records-the-current-correctness-mismatch
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
      (let [result (semantic-result @session (baseline/stack-query-index))
            compiled (scene/compile-scene result)]
        (is (value/nothing? result)
            "the versioned wrapper path still loses the supported traversal result")
        (is (empty? (:planes compiled)))
        (is (= :conflict (get-in compiled [:diagnostics 0 :kind]))))
      (finally
        (runtime/stop-clocks! session)))))
