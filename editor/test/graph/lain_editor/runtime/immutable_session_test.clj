(ns graph.lain-editor.runtime.immutable-session-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [graph.lain-editor.integration.editor-test :as fixture]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.server :as server]
            [propagators.compiler-2.session :as compiler]
            [propagators.compiler-2.session.host :as host]
            [propagators.compiler-2.session.files :as files]
            [propagators.compiler-2.session.operators :as operators]
            [propagators.cells.value :as value]
            [propagators.experimental.visualization.data :as data]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.high-level :as high-level]
            [propagators.stdlib.source-publication :as publication]))

(defn- prepare [source]
  (editor/prepare-compiler source []))

(deftest headless-compiler-attachment-extension-detachment-and-rerun
  (let [source "(define input) (define result (+ input 1))"
        editors (atom {})
        registered (editor/open-session! editors {:source source})
        detached (editor/owner-catalog editors)
        live (:session (get @detached (:sessionId registered)))
        boundary (host/boundary-for live)]
    (try
      (let [id (:sessionId registered)
            attached (editor/reopen! editors detached {:pane "compiler" :sessionId id})]
        (is (identical? live (:session (get @editors "compiler"))))
        (is (= "waiting" (:status (fixture/inspect-pane editors "compiler" fixture/display))))
        (host/submit! boundary
          (compiler/cell-input (compiler/binding-cell (host/current-session boundary) 'input) 10))
        (host/step! boundary)
        (is (= "11" (:value (fixture/inspect-pane editors "compiler" fixture/display))))
        (let [extended (editor/extend! editors
                         {:pane "compiler" :generation (:generation attached)
                          :source "(define extra (+ result 1))"})]
          (is (= id (:sessionId extended)))
          (is (identical? live (:session (get @editors "compiler"))))
          (is (= 12 (compiler/binding-value (host/current-session boundary) 'extra)))
          (editor/detach! editors detached {:pane "compiler" :generation (:generation extended)})
          (is (empty? @editors))
          (let [run-id (:run-id (compiler/session-identity (host/current-session boundary)))]
            (host/step! boundary)
            (editor/reopen! editors detached {:pane "reopened" :sessionId id})
            (let [restarted (editor/run! editors {:pane "reopened" :source source})]
              (is (= id (:sessionId restarted)))
              (is (not= run-id (:run-id (compiler/session-identity
                                        (host/current-session boundary)))))
              (is (identical? live (:session (get @editors "reopened"))))))))
      (finally (editor/close! editors) (editor/close! detached) (host/close! boundary)))))

(deftest immutable-editor-default-with-explicit-legacy-fallback
  (let [editors (atom {})]
    (try
      (let [run (server/command! editors {:op "run" :pane "compiler"
                                         :compilerSession true
                                         :source "(define result (+ 20 22))"})]
        (is (some? (host/boundary-for (:session (get @editors "compiler")))))
        (is (= "42" (:value (fixture/inspect-pane editors "compiler" fixture/display))))
        (is (string? (:generation run))))
      (let [run (server/command! editors {:op "run" :pane "legacy" :compilerSession false
                                         :source "(define result 7)"})]
        (is (nil? (host/boundary-for (:session (get @editors "legacy")))))
        (is (= "7" (:value (fixture/inspect-pane editors "legacy" fixture/display)))))
      (finally (editor/close! editors)))))

(deftest semantic-and-high-level-tracers-use-the-immutable-session
  (let [example (slurp "examples/lain/high_level_tracing/main.lain")
        source (str/replace-first example "(display" "(define result")
        editors (atom {})]
    (try
      (let [run (server/command! editors {:op "run" :pane "trace"
                                         :compilerSession true :source source})
            result (server/command! editors {:op "inspect" :pane "trace"
                                             :generation (:generation run) :mode "graph"})]
        (is (some? (host/boundary-for (:session (get @editors "trace")))))
        (is (= "ready" (:status result)))
        (is (= "semantic topology · 2 nodes" (get-in result [:cell :summary]))))
      (finally (editor/close! editors)))))

(deftest live-tracers-observe-updates-retraction-and-recovery-without-changing-values
  (let [program "(define step (network [x] (+ x 1)))
                 (define input) (def-cells output) (-> (step input) output)"
        traced (str "(load-lain \"propagators/compiler_2/runtime/lain/tracers.lain\")\n"
                    program "\n(define trace-result
                    (trace:high-level (trace:semantic input) \"input\" \"output\"))")
        prepared (prepare traced)
        boundary (host/boundary-for (:session prepared))
        plain (compiler/extend-session (compiler/create-session)
                (compiler/session-extension {:id :plain :files {"main" program}}))]
    (try
      (loop [untraced plain revision 1 candidates [3 5 value/nothing 7]]
        (when-let [candidate (first candidates)]
          (let [update (publication/active :trace-input revision candidate)
                untraced (:session
                          (compiler/advance untraced
                            [(compiler/cell-input (compiler/binding-cell untraced 'input) update)]))]
            (host/submit! boundary
              (compiler/cell-input
                (compiler/binding-cell (host/current-session boundary) 'input) update))
            (host/step! boundary)
            (let [current (host/current-session boundary)
                  output (data/payload (compiler/binding-value current 'output))
                  captured (data/payload (compiler/binding-value current 'trace-result))
                  graph (high-level/display-graph captured)
                  captured-values (:values (capture/root-graph captured))
                  values (into {} (map (fn [[id label]]
                                        [label (data/payload (get captured-values id))]))
                               (:nodes graph))
                  expected (if (value/nothing? candidate) value/nothing (inc candidate))]
              (is (= expected output))
              (is (= (data/payload (compiler/binding-value untraced 'output)) output))
              (is (= '#{input output} (set (vals (:nodes graph)))))
              (is (seq (:edges graph)))
              (is (= candidate (get values 'input)))
              (is (= expected (get values 'output))))
            (recur untraced (inc revision) (next candidates)))))
      (finally (host/close! boundary)))))

(deftest failed-extension-does-not-change-attached-session-or-generation
  (let [prepared (prepare "(define result 42)")
        editors (atom {"compiler" prepared})
        boundary (host/boundary-for (:session prepared))
        before (host/current-session boundary)]
    (try
      (is (thrown? Exception
            (editor/extend! editors {:pane "compiler" :generation (:generation prepared)
                                     :source "(define result 99)"})))
      (is (= (:generation prepared) (:generation (get @editors "compiler"))))
      (is (= 42 (compiler/binding-value (host/current-session boundary) 'result)))
      (is (= (compiler/source-files before)
             (compiler/source-files (host/current-session boundary))))
      (finally (editor/close! editors)))))
