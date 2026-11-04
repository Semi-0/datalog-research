(ns graph.lain-editor.workspace.file-bridge-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [graph.lain-editor.workspace.file-service-test :as fixture]
            [graph.lain-editor.workspace.file-service :as files]
            [graph.lain-editor.workspace.file-bridge :as bridge]
            [graph.lain-editor.workspace.commands :as commands]
            [graph.lain-editor.runtime.session :as runtime]
            [graph.lain-editor.server :as server]))

(deftest editor-open-file-supports-native-and-text-drafts-without-running
  (fixture/with-root
    (fn [root service]
      (let [registry (atom {}) snapshot {:desktop "desktop" :panes []}]
        (commands/command! registry root {:op "workspace-poll" :workspace "view" :snapshot snapshot})
        (doseq [path ["primitive.clj" "notes.txt"]]
          (files/write-file! service {:path path :source "unexecuted draft"})
          (let [queued (bridge/editor-command! registry service {:op "editor/open-file" :workspace "view" :path path})
                delivered (:command (commands/command! registry root {:op "workspace-poll" :workspace "view" :snapshot snapshot}))]
            (is (= path (:path delivered)))
            (is (= "unexecuted draft" (:source delivered)))
            (commands/command! registry root {:op "workspace-poll" :workspace "view" :snapshot snapshot
                                              :ack {:request (:request queued) :status "applied"}})))))))

(deftest file-editor-queue-is-explicit-and-acknowledged
  (fixture/with-root
    (fn [root service]
      (let [registry (atom {})
            snapshot {:desktop "desktop" :panes [{:id "pane" :generation "g1"}]}
            saved (files/write-file! service {:path "extension.lain" :source "(define next 2)"})]
        (commands/command! registry root {:op "workspace-poll" :workspace "view" :snapshot snapshot})
        (is (thrown? Exception (bridge/editor-command! registry service
                                                       {:op "editor/extend-file" :workspace "view" :pane "pane" :path "extension.lain"})))
        (let [queued (bridge/editor-command! registry service
                                             {:op "editor/extend-file" :workspace "view" :pane "pane" :generation "g1"
                                              :path "extension.lain" :version (:version saved)})
              delivered (:command (commands/command! registry root {:op "workspace-poll" :workspace "view" :snapshot snapshot}))]
          (is (= "queued" (:status queued)))
          (is (= (:request queued) (:request delivered)))
          (is (= "(define next 2)" (:source delivered)))
          (commands/command! registry root {:op "workspace-poll" :workspace "view" :snapshot snapshot
                                            :ack {:request (:request queued) :status "applied" :result {:generation "g2"}}})
          (let [receipt (bridge/editor-command! registry service
                                                {:op "editor/status" :workspace "view" :request (:request queued)})]
            (is (= "applied" (:status receipt)))
            (is (= "g2" (get-in receipt [:result :generation])))))))))

(deftest captured-file-and-primitive-bridges-extend-an-existing-owner
  (fixture/with-root
    (fn [_ service]
      (let [views (atom {})
            opened (server/command! views {:op "open-session" :source "(define result 1)"})
            owner (:sessionId opened)
            pane "file-bridge-pane"]
        (try
          (server/command! views {:op "reopen-session" :sessionId owner :pane pane})
          (let [saved (files/write-file! service {:path "extension.lain" :source "(define next (+ result 1))"})
                extended (bridge/extend-file! views service
                                              {:pane pane :generation (:generation opened) :path "extension.lain" :version (:version saved)})]
            (is (= owner (:sessionId extended)))
            (is (= (:runId opened) (:runId extended)))
            (is (= "2" (:value (server/command! views {:op "inspect" :sessionId owner :generation (:generation extended)
                                                       :display {:cell "next" :mode "value"}}))))
            (files/write-file! service {:path "extension.lain" :source "changed" :baseVersion (:version saved)})
            (is (thrown? Exception (bridge/extend-file! views service
                                                        {:pane pane :generation (:generation extended) :path "extension.lain" :version (:version saved)})))
            (let [suffix (str/replace (str (random-uuid)) "-" "")
                  namespace-name (str "bridge-native-" suffix)
                  path (str "bridge_native_" suffix ".clj")
                  text (str "(ns " namespace-name " (:require [propagators.compiler-2.compiler.basis :as basis]))\n"
                            "(defn bindings [] [['triple (basis/primitive-operator (fn [x] (* 3 x)))]])")
                  primitive-file (files/write-file! service {:path path :source text})
                  primitive-owner (bridge/extend-file! views service
                                                       {:pane pane :generation (:generation extended) :path path :version (:version primitive-file)
                                                        :entry (str namespace-name "/bindings") :revision 0})
                  result (server/command! views {:op "extend" :pane pane :generation (:generation primitive-owner)
                                                 :source "(define tripled (triple next))"})]
              (is (= owner (:sessionId result)))
              (is (= "6" (:value (server/command! views {:op "inspect" :sessionId owner :generation (:generation result)
                                                         :display {:cell "tripled" :mode "value"}}))))))
          (finally (server/command! views {:op "stop-session" :sessionId owner})))))))

(deftest saved-file-version-and-source-fence-run-before-runtime-mutation
  (fixture/with-root
    (fn [_ service]
      (let [views (atom {})
            opened (server/command! views {:op "open-session" :source "(define result 1)"})
            owner (:sessionId opened) pane "saved-run-pane"]
        (try
          (server/command! views {:op "reopen-session" :sessionId owner :pane pane})
          (let [saved (files/write-file! service {:path "main.lain" :source "(define result 2)"})
                updated (files/write-file! service {:path "main.lain" :source "(define result 3)" :baseVersion (:version saved)})]
            (binding [server/*files* service]
              (is (thrown? Exception (server/command! views {:op "run" :pane pane :generation (:generation opened)
                                                             :source "(define result 2)" :savedFile (select-keys saved [:path :version])})))
              (is (= (:generation opened) (:generation (server/command! views {:op "session-status" :sessionId owner}))))
              (is (thrown? Exception (server/command! views {:op "run" :pane pane :generation (:generation opened)
                                                             :source "(define result 9)" :savedFile (select-keys updated [:path :version])})))
              (let [ran (server/command! views {:op "run" :pane pane :generation (:generation opened)
                                                :source "(define result 3)" :savedFile (select-keys updated [:path :version])})]
                (is (= owner (:sessionId ran)))
                (is (not= (:runId opened) (:runId ran))))))
          (finally (server/command! views {:op "stop-session" :sessionId owner})))))))
