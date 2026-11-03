(ns graph.lain-editor.runtime.external-session-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.inspection.core :as inspection]
            [graph.lain-editor.integration.editor-test :as fixture]
            [propagators.compiler-2.runtime :as runtime]))

(defn- workspace-with [pane]
  (fixture/document pane (fixture/pane "spare" "(display 0)")))

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
                        #(editor/restore! editors
                           (workspace-with (fixture/pane "observer" "(display 9)")))]]
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

(deftest external-session-remains-discoverable-through-multiple-views
  (let [owner (editor/prepare "(display 7)" [])
        session (:session owner)
        editors (atom {}) detached (atom {})]
    (try
      (let [registration (editor/observe! editors
                           {:pane "initial" :session session :sessionId "main"
                            :source "(display 7)"})]
        (editor/detach! editors detached (assoc registration :pane "initial"))
        (editor/reopen! editors detached {:pane "first" :sessionId "main"})
        (is (= ["main"] (mapv :sessionId (:sessions (editor/list-detached detached)))))
        (editor/reopen! editors detached {:pane "second" :sessionId "main"})
        (is (identical? session (:session (get @editors "first"))))
        (is (identical? session (:session (get @editors "second"))))
        (editor/remove! editors "first")
        (is (= "7" (:value (fixture/inspect-pane editors "second" {:cell "@display" :mode "value"}))))
        (editor/close! editors)
        (is (= ["main"] (mapv :sessionId (:sessions (editor/list-detached detached)))))
        (editor/reopen! editors detached {:pane "third" :sessionId "main"})
        (is (= "7" (:value (fixture/inspect-pane editors "third" {:cell "@display" :mode "value"}))))
        (is (thrown-with-msg? Exception #"externally owned"
              (editor/stop-detached! detached {:sessionId "main"}))))
      (finally (editor/close! editors) (editor/close! detached)
               (editor/close-session! session)))))

(deftest workspace-restore-preserves-external-owners-and-reuses-their-view
  (let [owner (editor/prepare "(display 7)" [])
        session (:session owner)
        editors (atom {})
        touched (atom [])
        schedule runtime/schedule-clock-subscriptions!
        refresh runtime/refresh-trace-subscriptions!]
    (try
      (let [registration (editor/observe! editors
                           {:pane "observer" :session session :sessionId "main"
                            :source "(display 7)"})
            before @session]
        (with-redefs [runtime/schedule-clock-subscriptions!
                      (fn [s] (swap! touched conj s) (schedule s))
                      runtime/refresh-trace-subscriptions!
                      (fn [s] (swap! touched conj s) (refresh s))]
          (let [result (editor/restore! editors
                         (workspace-with (fixture/pane "observer" "(display 7)")))]
            (is (= (:generation registration) (get-in result [:generations "observer"])))
            (is (identical? session (:session (get @editors "observer")))))
          (let [result (editor/restore! editors
                         (workspace-with (fixture/pane "other" "(display 9)")))]
            (is (= #{} (set (keys (:generations result)))))
            (is (identical? session (:session (get @editors "observer")))))
          (is (not-any? #(identical? session %) @touched)
              "Restoring an editor cannot schedule or refresh the external owner")
          (is (identical? before @session)))
        (is (= "7" (:value (fixture/inspect-pane editors "observer" {:cell "@display" :mode "value"}))))
        (is (nil? (get @editors "other")) "Imported programs remain drafts")
        (is (thrown-with-msg? Exception #"externally owned"
              (editor/restore! editors
                (workspace-with (fixture/pane "observer" "(display 99)")))))
        (is (identical? session (:session (get @editors "observer")))))
      (finally (editor/close! editors) (editor/close-session! session)))))
