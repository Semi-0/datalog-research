(ns graph.lain-editor.runtime.harness-observer-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.subagents.harness-session :as hs]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.runtime.display :as display]
            [graph.lain-editor.inspection.core :as inspection]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.ids :as ids]
            [propagators.compiler-2.runtime.operators.network-history :as history]
            [propagators.semantic-trace.retention :as retention]
            [propagators.semantic-trace.observation :as observation]
            [propagators.experimental.visualization.extension :as visualization]))

(deftest main-harness-history-has-three-components-before-any-assignment
  (let [h (hs/open! "observer-check"
            {:pi-config {:plugin :pi-better-subagents :version "0.19.1"
                         :provider "gx10-blazux" :model "qwen3.8-flash-next"
                         :cwd "/app" :tools ["read" "bash"]}
             :extensions [[retention/extension {:outbox-id (ids/boundary-outbox-id)}]
                          [visualization/extension {:outbox-id (ids/boundary-outbox-id)}]
                          [display/extension {}] [history/extension {}]
                          [observation/extension {}]]})
        session (:session h) editors (atom {})]
    (try
      (runtime/refresh-trace-subscriptions! session)
      (let [{:keys [generation]} (editor/observe! editors
                 {:pane "main" :session session :sessionId "observer-check" :source ";; Observer"})
            result (inspection/inspect editors {:pane "main" :generation generation
                      :display {:cell "@display" :mode "graph" :direction "both"}})
            frames (get-in result [:replay :frames])]
        (is (seq frames) (pr-str (select-keys result [:value :error])))
        (is (<= (count frames) 10))
        (is (= #{"user" "evaluation-framework" "runtime"}
               (set (map :label (get-in (last frames) [:graph :nodes])))))
        (is (empty? (get-in (hs/artifacts h) [:facts :socket-in]))))
      (finally (editor/close! editors) (hs/close! h)))))
