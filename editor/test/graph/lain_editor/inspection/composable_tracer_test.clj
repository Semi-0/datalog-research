(ns graph.lain-editor.inspection.composable-tracer-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.server :as server]))

(deftest displayed-composition-uses-configured-tracer
  (let [editors (atom {})
        source (str "(load-primitive-environment \"editor/src/graph/lain_editor/harness_glue.clj\")\n"
                    "(load-lain \"examples/lain/subagents/composition.lain\")\n"
                    "(define result ((tracer:roots modifier:high-level modifier:with-values "
                    "(modifier:select (list \"user\" \"runtime\" \"evaluation-framework\"))) user))")]
    (try
      (let [run (server/command! editors {:op "run" :pane "test" :source source :extensions []})
            inspected (server/command! editors {:op "inspect" :pane "test" :generation (:generation run)
                                                :display {:cell "result" :mode "graph" :direction "both"}})
            labels (set (map :label (get-in inspected [:graph :nodes])))]
        (is (= #{"user" "runtime" "evaluation-framework"} labels)
            (pr-str (select-keys inspected [:value :status :diagnostics]))))
      (finally (server/command! editors {:op "close" :pane "test"})))))
