(ns graph.lain-editor.runtime.detached-session-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [graph.json :as json]
            [graph.lain-editor.runtime.session :as session]
            [graph.lain-editor.server :as server]
            [graph.lain-editor.layout.graph-layout :as layout]
            [graph.lain-editor.integration.editor-test :as fixture]
            [propagators.compiler-2.runtime :as runtime]))

(deftest live-session-ownership-and-failures
  (let [editors (atom {}) detached (atom {}) layouts (atom {}) developments (atom {})
        command! #(server/command! editors layouts developments detached %)
        source "(define input) (define result (+ input 1))"]
    (try
      (let [run (command! {:op "run" :pane "a" :source source})
            extended (command! {:op "extend" :pane "a" :generation (:generation run) :source "(-> 10 input)"})
            id (:sessionId run) live (:session (get @editors "a"))]
        (is (string? id))
        (is (= id (:sessionId extended)))
        (is (not= (:generation run) (:generation extended)))
        (is (thrown? Exception (command! {:op "detach-session" :pane "a" :generation (:generation run)})))
        (is (identical? live (:session (get @editors "a"))))
        (is (empty? @detached))
        (is (thrown? Exception (server/command! editors {:op "detach-session" :pane "a" :generation (:generation extended)})))
        (with-redefs [layout/close-pane! (fn [& _] (throw (Exception. "Layout cleanup failed")))]
          (is (thrown? Exception (command! {:op "detach-session" :pane "a" :generation (:generation extended)})))
          (is (identical? live (:session (get @editors "a"))))
          (is (empty? @detached)))
        (let [cleared (atom []) original layout/close-pane!]
          (with-redefs [layout/close-pane! (fn [registry pane]
                                           (swap! cleared conj pane)
                                           (original registry pane))]
            (command! {:op "detach-session" :pane "a" :generation (:generation extended)})
            (is (= ["a"] @cleared))
            (command! {:op "reopen-session" :sessionId id :pane "a"})))
        (with-redefs [session/load-checked! (fn [& _] (throw (Exception. "Unexpected load")))
                      session/close-session! (fn [& _] (throw (Exception. "Unexpected close")))
                      runtime/schedule-clock-subscriptions! (fn [& _] (throw (Exception. "Unexpected schedule")))
                      runtime/refresh-trace-subscriptions! (fn [& _] (throw (Exception. "Unexpected subscribe")))]
          (is (= {:sessionId id} (command! {:op "detach-session" :pane "a" :generation (:generation extended)})))
          (is (empty? @editors))
          (is (= [{:sessionId id :pane "a" :source source :extensions ["(-> 10 input)"]}]
                 (:sessions (command! {:op "list-sessions"}))))
          (is (= (:generation extended) (:generation (command! {:op "reopen-session" :sessionId id :pane "b"}))))
          (is (identical? live (:session (get @editors "b"))))
          (is (= "11" (:value (fixture/inspect-pane editors "b" fixture/display)))))
        (doseq [command [{:op "reopen-session" :sessionId id :pane "c"}
                         {:op "stop-session" :sessionId id}
                         {:op "reopen-session" :sessionId "unknown" :pane "c"}]]
          (let [before @editors]
            (is (thrown? Exception (command! command)))
            (is (= before @editors))))
        (command! {:op "detach-session" :pane "b" :generation (:generation extended)})
        (command! {:op "run" :pane "occupied" :source "(display 99)"})
        (let [before @detached]
          (is (thrown? Exception (command! {:op "reopen-session" :sessionId id :pane "occupied"})))
          (is (= before @detached)))
        (command! {:op "restore" :workspace (fixture/document (fixture/pane "x" "(display 1)") (fixture/pane "y" "(display 2)"))})
        (is (identical? live (:session (get @detached id))))
        (let [original session/close-session! closed (atom [])]
          (with-redefs [session/close-session! (fn [s] (swap! closed conj s) (original s))]
            (is (= {:stopped id} (command! {:op "stop-session" :sessionId id})))
            (is (= [live] @closed))))
        (is (empty? @detached)))
      (finally (session/close! editors) (session/close! detached)))))

(deftest detached-clocks-and-inspection-history
  (let [editors (atom {}) detached (atom {})
        command! #(server/command! editors (atom {}) (atom {}) detached %)
        source (str/replace (slurp "examples/lain/network_replay/clock.lain") "(clock-in 1000)" "(clock-in 20)")
        display {:cell "@display" :mode "graph" :direction "both"}]
    (try
      (let [run (command! {:op "run" :pane "clock" :source source})
            live (:session (get @editors "clock"))
            initial (get-in (fixture/inspect-pane editors "clock" display) [:replay :frames])]
        (is (seq initial))
        (command! {:op "detach-session" :pane "clock" :generation (:generation run)})
        (Thread/sleep 250)
        (command! {:op "reopen-session" :sessionId (:sessionId run) :pane "reopened"})
        (is (identical? live (:session (get @editors "reopened"))))
        (let [latest (get-in (fixture/inspect-pane editors "reopened" display) [:replay :frames])]
          (is (> (count latest) (count initial)))
          (is (= initial (subvec latest 0 (count initial))))))
      (finally (session/close! editors) (session/close! detached)))))

(defn post! [service command]
  (let [connection (.openConnection (.toURL (java.net.URI. (str "http://127.0.0.1:" (:port service) "/api"))))]
    (.setRequestMethod connection "POST")
    (.setDoOutput connection true)
    (with-open [out (.getOutputStream connection)] (.write out (.getBytes (json/write-json command) "UTF-8")))
    (let [status (.getResponseCode connection)]
      {:status status :body (json/read-json (slurp (if (< status 400) (.getInputStream connection) (.getErrorStream connection))))})))

(deftest http-contract-and-shutdown
  (let [service (server/start! 0)]
    (try
      (let [run (get-in (post! service {:op "run" :pane "a" :source "(display 7)"}) [:body :result]) id (:sessionId run)]
        (is (string? id))
        (is (= {:status 200 :body {:ok true :result {:sessionId id}}}
               (post! service {:op "detach-session" :pane "a" :generation (:generation run)})))
        (is (= id (get-in (post! service {:op "list-sessions"}) [:body :result :sessions 0 :sessionId])))
        (is (= (assoc run :source "(display 7)" :extensions [])
               (get-in (post! service {:op "reopen-session" :sessionId id :pane "b"}) [:body :result])))
        (let [stale (post! service {:op "reopen-session" :sessionId id :pane "c"})]
          (is (= 400 (:status stale)))
          (is (false? (get-in stale [:body :ok])))
          (is (string? (get-in stale [:body :error]))))
        (post! service {:op "detach-session" :pane "b" :generation (:generation run)})
        (is (= id (get-in (post! service {:op "stop-session" :sessionId id}) [:body :result :stopped]))))
      (post! service {:op "run" :pane "attached" :source "(display 1)"})
      (let [run (get-in (post! service {:op "run" :pane "detached" :source "(display 2)"}) [:body :result])]
        (post! service {:op "detach-session" :pane "detached" :generation (:generation run)}))
      (let [original session/close-session! closed (atom [])]
        (with-redefs [session/close-session! (fn [s] (swap! closed conj s) (original s))]
          ((:close service))
          (is (= 2 (count @closed)))))
      (is (empty? @(:editors service)))
      (is (empty? @(:detached service)))
      (finally ((:close service))))))
