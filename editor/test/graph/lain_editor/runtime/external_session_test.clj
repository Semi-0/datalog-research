(ns graph.lain-editor.runtime.external-session-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.inspection.core :as inspection]))

(deftest external-owner-remains-live-through-editor-lifecycle
  (let [owner (editor/prepare "(display 7)" [])
        session (:session owner)
        editors (atom {}) detached (atom {})]
    (try
      (let [{:keys [generation]} (editor/observe! editors
               {:pane "observer" :session session :sessionId "main" :source "(display 7)"})
            before @session]
        (is (identical? session (:session (get @editors "observer"))))
        (is (= "7" (:value (inspection/inspect editors
                      {:pane "observer" :generation generation
                       :display {:cell "@display" :mode "value"}}))))
        (doseq [mutate [#(editor/run! editors {:pane "observer" :source "(display 9)"})
                        #(editor/extend! editors {:pane "observer" :generation generation :source "(display 9)"})
                        #(editor/spawn-slots! editors {:pane "observer" :generation generation})
                        #(editor/restore! editors {})]]
          (is (thrown-with-msg? Exception #"externally owned" (mutate))))
        (is (identical? before @session))
        (editor/detach! editors detached {:pane "observer" :generation generation})
        (is (thrown-with-msg? Exception #"externally owned"
              (editor/stop-detached! detached {:sessionId "main"})))
        (is (= 1 (count (:sessions (editor/list-detached detached)))))
        (editor/reopen! editors detached {:pane "reopened" :sessionId "main"})
        (is (identical? session (:session (get @editors "reopened"))))
        (with-redefs [editor/close-session! (fn [_] (throw (ex-info "Owner cleanup acquired" {})))]
          (editor/remove! editors "reopened")
          (editor/observe! editors {:pane "again" :session session :sessionId "main" :source "(display 7)"})
          (editor/close! editors))
        (is (empty? @editors))
        (is (identical? before @session))
        (editor/load-checked! session "(define still-live 8)")
        (is (not (identical? before @session))))
      (finally (editor/close-session! session)))))
