(ns graph.lain-editor.workspace.commands-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.workspace.commands :as commands]
            [graph.lain-editor.server :as server]
            [graph.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(def snapshot {:desktop "desktop-1" :selected "a" :panes [{:id "a" :filename "draft.lain"}]})
(defn- poll!
  ([registry id ack] (poll! registry id snapshot ack))
  ([registry id current-snapshot ack]
   (commands/command! registry "." {:op "workspace-poll" :workspace id
                                     :snapshot current-snapshot :ack ack})))

(deftest file-load-is-scoped-acknowledged-and-does-not-evaluate
  (let [registry (atom {}) path "examples/lain/top_down/stack-design.lain"]
    (poll! registry "first" nil) (poll! registry "second" nil)
    (let [queued (commands/command! registry "." {:op "load-file" :workspace "first" :path path})
          delivered (:command (poll! registry "first" nil))
          ack {:request (:request queued) :status "applied"}]
      (is (= "queued" (:status queued)))
      (is (= (slurp path) (:source delivered)))
      (is (= "stack-design.lain" (:filename delivered)))
      (is (= "desktop-1" (:desktop delivered)))
      (is (= delivered (:command (poll! registry "first" nil))) "Lost replies are redelivered")
      (is (nil? (:command (poll! registry "second" nil))))
      (is (nil? (:command (poll! registry "first" ack))))
      (is (nil? (:command (poll! registry "first" ack))) "Acknowledgements are idempotent")
      (is (= "applied" (:status (commands/command! registry "." {:op "load-status" :workspace "first" :request (:request queued)}))))
      (is (not (contains? queued :source))))))

(deftest disconnected-and-unsafe-loads-are-rejected
  (let [registry (atom {})]
    (is (thrown? Exception (commands/command! registry "." {:op "load-file" :workspace "missing" :path "x.lain"})))
    (poll! registry "tab" nil)
    (doseq [path ["../outside.lain" "/tmp/outside.lain" "deps.edn" "missing.lain"]]
      (is (thrown? Exception (commands/command! registry "." {:op "load-file" :workspace "tab" :path path}))))
    (swap! registry assoc-in ["tab" :seen] 0)
    (is (= {:workspaces []} (commands/command! registry "." {:op "workspaces"})))
    (is (thrown? Exception (commands/command! registry "." {:op "load-status" :workspace "tab" :request "gone"})))))

(deftest closed-block-metadata-and-reopen-use-the-acknowledged-channel
  (let [registry (atom {})
        snapshot {:desktop "desktop-1" :selected "active" :panes []
                  :closedBlocks [{:id "archive-a" :order 3
                                  :title "Draft" :filename "draft.lain"}]}
        _ (poll! registry "tab" snapshot nil)
        listed (commands/command! registry "." {:op "closed-blocks" :workspace "tab"})
        queued (commands/command! registry "." {:op "reopen-closed" :workspace "tab"
                                                :archive "archive-a" :desktop "desktop-1"})
        delivered (:command (poll! registry "tab" snapshot nil))]
    (is (= (:closedBlocks snapshot) (:closedBlocks listed)))
    (is (= "reopen-closed" (:kind delivered)))
    (is (= "archive-a" (:archive delivered)))
    (is (= (:request queued) (:request delivered)))
    (is (= delivered (:command (poll! registry "tab" snapshot nil)))
        "unacknowledged commands are redelivered")
    (is (nil? (:command (poll! registry "tab" snapshot
                               {:request (:request queued) :status "applied"}))))
    (is (= "applied" (:status (commands/command! registry "."
                                                  {:op "reopen-status" :workspace "tab"
                                                   :request (:request queued)}))))
    (is (thrown? Exception
                 (commands/command! registry "." {:op "reopen-closed" :workspace "tab"
                                                   :archive "missing" :desktop "desktop-1"})))
    (is (thrown? Exception
                 (commands/command! registry "." {:op "reopen-closed" :workspace "tab"
                                                   :archive "archive-a" :desktop "other"})))))

(deftest failed-requests-and-bounded-queues-remain-inspectable
  (let [registry (atom {})
        submit #(commands/command! registry "." {:op "load-file" :workspace "tab"
                                                 :path "examples/lain/top_down/stack/example.lain"})]
    (poll! registry "tab" nil)
    (let [request (:request (submit))]
      (poll! registry "tab" nil)
      (poll! registry "tab" {:request request :status "failed" :error "Desktop changed"})
      (is (= {:request request :pane request :status "failed" :error "Desktop changed"
              :path "examples/lain/top_down/stack/example.lain"}
             (commands/command! registry "." {:op "load-status" :workspace "tab" :request request}))))
    (dotimes [_ 16] (submit))
    (is (thrown-with-msg? Exception #"queue is full" (submit)))
    (is (thrown-with-msg? Exception #"acknowledgement"
                          (poll! registry "tab" {:request "unknown" :status "applied"})))))

(defn- post [base origin command]
  (with-open [client (HttpClient/newHttpClient)]
    (let [request (-> (HttpRequest/newBuilder (URI. (str base "/api")))
                      (.header "Content-Type" "application/json")
                      (.header "Origin" origin)
                      (.POST (HttpRequest$BodyPublishers/ofString (json/write-json command))) .build)
          reply (.send client request (HttpResponse$BodyHandlers/ofString))]
      [(.statusCode reply) (json/read-json (.body reply))])))

(deftest http-command-channel-keeps-existing-origin-policy
  (let [service (server/start! 0) base (str "http://127.0.0.1:" (:port service))]
    (try
      (is (= 400 (first (post base "https://untrusted.example" {:op "workspaces"}))))
      (is (= 200 (first (post base base {:op "workspace-poll" :workspace "http-tab" :snapshot snapshot}))))
      (let [[status body] (post base base {:op "load-file" :workspace "http-tab"
                                          :path "examples/lain/top_down/stack/example.lain"})]
        (is (= 200 status))
        (is (= "queued" (get-in body [:result :status])))
        (is (empty? @(:editors service)) "Loading source never starts a runtime"))
      (finally ((:close service))))))
