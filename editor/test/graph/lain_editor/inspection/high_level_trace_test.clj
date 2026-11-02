(ns graph.lain-editor.inspection.high-level-trace-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [graph.lain-editor.server :as server]))

(defn- inspect-source [source]
  (let [editors (atom {})
        pane (str (java.util.UUID/randomUUID))
        run (server/command! editors {:op "run" :pane pane :source source :extensions []})]
    (server/command! editors {:op "inspect" :pane pane
                              :generation (:generation run) :mode "graph"})))

(deftest public-tracer-through-editor-session
  (let [example (slurp "examples/lain/high_level_tracing/main.lain")
        source (str/replace-first example "(display" "(define result")
        result (inspect-source source)
        displayed (server/command! (atom {}) {:op "run" :pane "example"
                                              :source example :extensions []})]
    (is (= "ready" (:status result)))
    (is (= "semantic topology · 2 nodes" (get-in result [:cell :summary])))
    (is (= "graph" (:displayMode displayed)))))

(deftest ^:experimental-harness harness-component-overview
  (let [result (inspect-source
                (str "(load-lain \"examples/lain/subagents/composition.lain\")\n"
                     "(define result (trace:high-level (trace:semantic user) "
                     "\"user\" \"runtime\" \"evaluation-framework\" "
                     "\"pi\" \"harbor\" \"socket\"))"))]
    (is (= "ready" (:status result)))
    (is (= "semantic topology · 6 nodes" (get-in result [:cell :summary])))))

(deftest pending-source-stays-pending
  (let [result (inspect-source
                (str "(load-lain \"propagators/compiler_2/runtime/lain/tracers.lain\")\n"
                     "(def-cells pending)\n"
                     "(define result (trace:high-level pending \"input\"))"))]
    (is (not= "ready" (:status result)))
    (is (not= "contradiction" (:status result)))))

(deftest invalid-boundary-fails-explicitly
  (let [source (-> (slurp "examples/lain/high_level_tracing/main.lain")
                   (str/replace "\"output\"" "\"absent-component\""))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boundary is absent"
                          (server/command! (atom {}) {:op "run" :pane "invalid"
                                                       :source source :extensions []})))))
