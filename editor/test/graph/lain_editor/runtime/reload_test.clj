(ns graph.lain-editor.runtime.reload-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.server :as server]
            [propagators.compiler-2.session :as compiler]
            [propagators.compiler-2.session.host :as host]
            [propagators.compiler-2.session.connected :as connected]
            [propagators.compiler-2.session.dependencies :as dependency]
            [propagators.compiler-2.session.operators :as operators]))

(deftest reload-pins-old-applications-and-selects-a-child-for-future-source
  (let [editors (atom {})
        source "(define delayed (let-cell [port] port)) (define f (network [n] (+ n 1))) (define call-f (network [n] (f n))) (define in-flight (call-f delayed))"
        revised (.replace source "(+ n 1)" "(+ n 2)")
        run (server/command! editors {:op "run" :pane "p" :source source})
        owner (host/boundary-for (:session (editor/require-pane editors "p")))
        current #(host/current-session owner)
        read-name #(compiler/binding-value (current) %)
        delayed (compiler/binding-cell (current) 'delayed)]
    (try
      (let [reload (server/command! editors {:op "reload" :sessionId (:sessionId run) :generation (:generation run) :source revised})
            extended (server/command! editors {:op "extend" :sessionId (:sessionId run) :generation (:generation reload)
                                                :source "(define fresh (call-f delayed))"})]
        (is (= (:runId run) (:runId reload) (:runId extended)))
        (is (= (:sessionId run) (:sessionId reload) (:sessionId extended)))
        (is (= ["f" "call-f"] (:reloaded reload)))
        (is (= delayed (compiler/binding-cell (current) 'delayed)))
        (host/submit! owner (compiler/cell-input delayed 40))
        (host/step! owner)
        (is (= 41 (read-name 'in-flight)))
        (is (= 42 (read-name 'fresh)))
        (let [g (:generation extended) rejected (str revised " (display 3)")]
          (is (thrown-with-msg? Exception #"wiring" (editor/reload! editors {:pane "p" :generation g :source rejected})))
          (is (= g (:generation (editor/session-status editors {:pane "p"}))))
          (is (= [] (:reloaded (editor/reload! editors {:pane "p" :generation g :source revised}))))))
      (finally (editor/close! editors)))))

(deftest running-site-uses-new-revision-on-next-packet
  (let [editors (atom {})
        src "(define wait (let-cell [port] port)) (define packet (let-cell [port] port)) (define route (network [req] wait)) (define output (editor:live-call route packet))"
        run (server/command! editors {:op "run" :pane "p" :source src})
        owner (host/boundary-for (:session (editor/require-pane editors "p")))
        current #(host/current-session owner)
        cell #(compiler/binding-cell (current) %)
        post! (fn [id] (host/publish-input! owner
                         (fn [_] [(compiler/publication-input (cell 'packet) {:id id})])))]
    (try
      (post! "old")
      (server/command! editors {:op "reload" :pane "p" :generation (:generation run)
                                :source (.replace src "[req] wait" "[req] 42")})
      (host/publish-input! owner (fn [_] [(compiler/publication-input (cell 'wait) 41)]))
      (is (= 41 (connected/payload (compiler/binding-value (current) 'output))))
      (post! "new")
      (is (= 42 (connected/payload (compiler/binding-value (current) 'output))))
      (is (= (:runId run) (:runId (editor/session-status editors {:pane "p"}))))
      (finally (editor/close! editors)))))

(deftest loaded-lain-source-can-revise-a-caller-in-the-entry
  (let [editors (atom {}) dir (.toFile (java.nio.file.Files/createTempDirectory "editor-reload" (make-array java.nio.file.attribute.FileAttribute 0)))
        module (java.io.File. dir "policy.lain")
        source "(load-lain \"policy.lain\") (define wrap (network [x] (policy x)))"]
    (binding [editor/*source-root* (.getPath dir)]
      (try
        (spit module "(define policy (network [x] (+ x 1)))")
        (let [run (editor/run! editors {:pane "p" :source source})]
          (spit module "(define policy (network [x] (+ x 2)))")
          (let [reload (editor/reload! editors {:pane "p" :generation (:generation run) :source source})]
            (is (= #{"policy" "wrap"} (set (:reloaded reload))))
            (is (= (:runId run) (:runId reload)))
            (spit module "(define policy (network [x y] x))")
            (is (thrown-with-msg? Exception #"argument interface" (editor/reload! editors {:pane "p" :generation (:generation reload) :source source})))
            (is (= (:generation reload) (:generation (editor/session-status editors {:pane "p"}))))))
        (finally (editor/close! editors) (.delete module) (.delete dir))))))

(deftest reload-preserves-a-pending-callback-and-user-history
  (let [editors (atom {}) command #(server/command! editors %)
        src "(define fetch (let-cell [port] port)) (define chat-log (let-cell [port] port)) (define route (network [req] (fetch req))) (define packet (let-cell [port] port)) (define result (editor:live-call route packet))"
        revised (.replace src "(fetch req)" "42")
        run (command {:op "run" :pane "p" :source src})
        owner (host/boundary-for (:session (editor/require-pane editors "p")))
        current #(host/current-session owner)
        log (compiler/binding-cell (current) 'chat-log)
        emit (promise) stopped (atom 0)]
    (try
      (host/extend! owner (compiler/session-extension
                            {:id :controlled-io
                             :interfaces {[:user "chat"] {:cell log :kind :user-message}}
                             :primitives {'fetch (operators/async-op (fn [make] (make :fetch)))}
                             :dependencies {:fetch (dependency/callback-dependency
                                                      (fn [_ send!] (deliver emit send!)
                                                        (fn [] (swap! stopped inc) {:status :closed})))}}))
      (host/publish-input! owner (fn [_] [(compiler/publication-input log connected/empty-list)]))
      (let [post (fn [id text g] (command {:op "user-message" :sessionId (:sessionId run)
                                           :generation g :runId (:runId run) :inputId "chat" :messageId id :text text}))
            send-packet (fn [id] (host/publish-input! owner (fn [s] [(compiler/publication-input
                                                                       (compiler/binding-cell s 'packet) {:id id})])))]
        (post "m1" "before" (:generation run))
        (send-packet "old")
        (host/step! owner)
        (when-not (fn? (deref emit 5000 nil))
          (throw (ex-info "Controlled callback did not start" {:receipts (count (host/receipts owner)) :failures (map ex-message (host/failures owner))})))
        (let [reloaded (command {:op "reload" :pane "p" :generation (:generation run) :source revised})]
          (is (zero? @stopped))
          (post "m2" "after" (:generation reloaded))
          (let [messages (get-in (host/interface-values owner) [[:user "chat"] :value])]
            (is (= ["before" "after"] [(get-in messages [:car :payload]) (get-in messages [:cdr :car :payload])])))
          (send-packet "new")
          (is (= 42 (connected/payload (compiler/binding-value (current) 'result))))
          (is (= :accepted (@emit 41)))
          (host/step! owner)
          (is (= 42 (connected/payload (compiler/binding-value (current) 'result))))
          (is (zero? @stopped))))
      (finally (editor/close! editors)))))

(def ^:dynamic *native-loads* nil)

(defn counted-primitive-environment []
  (swap! *native-loads* inc)
  [])

(deftest changed-native-artifact-cannot-run-its-factory-during-reload
  (let [editors (atom {})
        dir (.toFile (java.nio.file.Files/createTempDirectory "editor-reload-native"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        module (java.io.File. dir "test.clj")
        source "(load-primitive-environment \"test.clj\" :graph.lain-editor.runtime.reload-test/counted-primitive-environment 1) (define k 1)"]
    (binding [editor/*source-root* (.getPath dir) *native-loads* (atom 0)]
      (try
        (spit module ";; original")
        (let [run (editor/run! editors {:pane "p" :source source})]
          (is (= 1 @*native-loads*))
          (spit module ";; updated")
          (is (thrown-with-msg? Exception #"native primitive import changed"
                (editor/reload! editors {:pane "p" :generation (:generation run) :source source})))
          (is (= 1 @*native-loads*))
          (is (= (:generation run) (:generation (editor/session-status editors {:pane "p"})))))
        (finally (editor/close! editors) (.delete module) (.delete dir))))))
