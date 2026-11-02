(ns propagators.compiler-2-socket-extension-test
  (:require [clojure.test :refer [deftest is testing]]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.primitives :as primitives]
            [examples.lain.subagents.socket-extension :as policy]
            [examples.lain.subagents.socket-transport :as transport]
            [graph.json :as json]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.network :as net]
            [propagators.runner :as runner])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.net Socket ConnectException]
           [java.nio.charset StandardCharsets]))

(def session-id "socket-test")

(defn command [id order kind fields]
  (facts/fact session-id id order kind fields))

(defn log-of [& events] (facts/from-facts session-id events))

(defn events [log] (facts/ordered-facts log))

(defn with-fact-runner [id f]
  (binding [runner/*advance-transform*
            (runner/compose-advance-transforms runner/*advance-transform*
                                               (primitives/runner-transform id))]
    (f)))

(defn checked-load! [session source]
  (let [result (loader/load-source! session source)]
    (when-let [error (get-in result [:load-result :error])]
      (throw (ex-info "Socket extension test Lain source failed" {:error error}))))
  session)

(defn binding-value [session name]
  (let [state @session
        network (:program/net state)
        id (env/resolve-binding-id network (:program/env state) name)]
    (net/network-cell-strongest network id)))

(defn publish! [session binding log]
  (let [state @session
        id (env/resolve-binding-id (:program/net state) (:program/env state) binding)]
    (primitives/publish-facts! session session-id id log)))

(deftest command-normalization-is-ordered-idempotent-and-keeps-rejections-separate
  (let [submit (command "submit" 1 :assignment/submitted {:assignment-id "a" :task "run tests"})
        reorder (command "reorder" 2 :assignment/reordered {:assignment-ids ["a"]})
        cancel (command "cancel" 3 :assignment/cancel-requested {:assignment-id "a"})
        bad (command "bad" 4 :socket/command
                     {:command-kind "assignment/unknown" :command {}})
        normalized (policy/normalize-commands session-id (log-of cancel bad submit reorder submit))]
    (is (= ["submit" "reorder" "cancel" "bad"] (mapv :id (events normalized))))
    (is (= 3 (count (events (policy/derive-assignments session-id normalized)))))
    (is (= [cancel] (events (policy/derive-controls session-id normalized))))
    (is (= :unsupported-command (get-in (last (events normalized)) [:failure :reason])))
    (is (= [:user/command-accepted :user/command-accepted :user/command-accepted :user/command-rejected]
           (mapv :kind (events (policy/describe-commands session-id normalized)))))
    (is (= normalized (policy/normalize-commands session-id normalized)))))

(deftest malformed-domain-commands-do-not-fabricate-assignment-or-control
  (doseq [[kind fields reason]
          [[:assignment/submitted {:assignment-id "" :task "x"} :invalid-assignment-id]
           [:assignment/submitted {:assignment-id "a" :task ""} :invalid-task]
           [:assignment/submitted {:assignment-id "a" :task "x" :extra true} :invalid-command-fields]
           [:assignment/reordered {:assignment-ids ["a" "a"]} :invalid-assignment-order]
           [:assignment/cancel-requested {} :invalid-command-fields]]]
    (let [normalized (policy/normalize-commands session-id (log-of (command "bad" 0 kind fields)))]
      (is (= reason (get-in (first (events normalized)) [:failure :reason])))
      (is (empty? (events (policy/derive-assignments session-id normalized))))
      (is (empty? (events (policy/derive-controls session-id normalized))))
      (is (= :user/command-rejected
             (:kind (first (events (policy/describe-commands session-id normalized)))))))))

(deftest feedback-is-bounded-and-cannot-serialize-domain-objects
  (let [feedback (policy/describe-assignments
                  session-id
                  (log-of (command "reject" 4 :assignment/rejected
                                   {:assignment-id "a" :failure {:reason :task-conflict}})))
        requests (policy/encode-feedback session-id feedback)]
    (is (= :socket/write (:kind (first (events requests)))))
    (is (= :assignment/rejected
           (get-in (first (events requests)) [:payload :source-kind])))
    (is (= :task-conflict
           (get-in (first (events requests)) [:payload :details :failure :reason]))))
  (is (thrown? Exception
               (policy/encode-feedback session-id
                 (log-of (command "object" 0 :runtime/status {:value (facts/empty-log session-id)})))))
  (is (thrown? Exception
               (policy/encode-feedback session-id
                 (log-of (command "huge" 0 :runtime/status {:message (apply str (repeat 9000 "x"))})))))
  (doseq [unresolved [:bool4/nothing :bool4/contradiction Double/NaN Double/POSITIVE_INFINITY]]
    (is (thrown? Exception
                 (policy/encode-feedback session-id
                   (log-of (command "invalid" 0 :runtime/status {:value unresolved}))))))
  (is (thrown? Exception
               (policy/normalize-commands session-id (facts/empty-log "foreign")))))

(deftest real-lain-user-network-installs-the-six-session-primitives
  (let [session
        (with-fact-runner
          session-id
          #(-> (loader/load-session-from-source
                 "0" {:extensions [(policy/session-extension session-id)]})
               (checked-load!
                "(load-lain \"examples/lain/subagents/user-socket.lain\")
                 (define socket (make-socket))
                 (define actor (user-from-socket socket))
                 (define incoming)
                 (<-> incoming (socket:observations socket))
                 (define assignments (user:assignments actor))
                 (define controls (user:controls actor))
                 (define requests (socket:requests socket))")))]
    (publish! session 'incoming
              (log-of (command "one" 1 :assignment/submitted {:assignment-id "a" :task "x"})))
    (is (= [:assignment/submitted] (mapv :kind (events (binding-value session 'assignments)))))
    (is (empty? (events (binding-value session 'controls))))
    (is (seq (events (binding-value session 'requests))))
    (publish! session 'incoming
              (log-of (command "cancel" 2 :assignment/cancel-requested {:assignment-id "a"})))
    (is (= [:assignment/cancel-requested] (mapv :kind (events (binding-value session 'controls)))))
    (let [before (binding-value session 'requests)]
      (publish! session 'incoming
                (log-of (command "one" 1 :assignment/submitted {:assignment-id "a" :task "x"})))
      (is (= before (binding-value session 'requests))))
    (let [before @session]
      (is (thrown? Exception (publish! session 'incoming (facts/empty-log "foreign"))))
      (is (identical? before @session)))))

(defn connect [boundary]
  (let [socket (Socket. "127.0.0.1" (int (transport/port boundary)))]
    (.setSoTimeout socket 2000)
    {:socket socket
     :reader (BufferedReader. (InputStreamReader. (.getInputStream socket) StandardCharsets/UTF_8))
     :writer (OutputStreamWriter. (.getOutputStream socket) StandardCharsets/UTF_8)}))

(defn raw-line! [{:keys [writer reader]} text]
  (.write ^OutputStreamWriter writer (str text "\n"))
  (.flush ^OutputStreamWriter writer)
  (some-> (.readLine ^BufferedReader reader) json/read-json))

(defn wire-command [id order kind fields]
  (merge {:session session-id :id id :order order :kind kind} fields))

(defn exchange! [client command] (raw-line! client (json/write-json command)))

(defn await-disconnect [boundary]
  (let [deadline (+ (System/nanoTime) 2000000000)]
    (loop []
      (if (or (zero? (:connections (transport/snapshot boundary)))
              (> (System/nanoTime) deadline))
        (transport/snapshot boundary)
        (do (Thread/sleep 5) (recur))))))

(deftest owned-loopback-boundary-separates-wire-and-domain-rejection
  (let [published (atom nil)
        boundary (transport/start! session-id {:mode :live :on-observation #(reset! published %)})
        client (connect boundary)]
    (try
      (is (pos? (transport/port boundary)))
      (is (= :invalid-json (keyword (get-in (raw-line! client "{") [:error :reason]))))
      (is (nil? @published))
      (.write (.getOutputStream ^Socket (:socket client))
              (byte-array [(unchecked-byte 195) (byte 10)]))
      (.flush (.getOutputStream ^Socket (:socket client)))
      (is (= "invalid-utf8"
             (get-in (json/read-json (.readLine ^BufferedReader (:reader client))) [:error :reason])))
      (is (false? (:ok (exchange! client (wire-command "foreign" 0 "assignment/submitted"
                                                       {:session "other" :assignment-id "a" :task "x"})))))
      (is (nil? @published))
      (is (= "received" (:status (exchange! client (wire-command "unknown" 1 "unknown/command" {})))))
      (let [normalized (policy/normalize-commands session-id @published)
            feedback (policy/describe-commands session-id normalized)]
        (is (= :domain (get-in (first (events normalized)) [:failure :category])))
        (is (empty? (events (policy/derive-assignments session-id normalized))))
        (is (= 1 (transport/send! boundary (first (events feedback)))))
        (is (= "user/command-rejected"
               (:kind (json/read-json (.readLine ^BufferedReader (:reader client)))))))
      (finally
        (.close ^Socket (:socket client))
        (transport/close! boundary)))
    (is (:closed? (transport/snapshot boundary)))
    (is (= 0 (:connections (transport/snapshot boundary))))
    (is (thrown? ConnectException (Socket. "127.0.0.1" (int (transport/port boundary)))))))

(deftest socket-observations-deduplicate-and-disconnect-does-not-cancel
  (let [boundary (transport/start! session-id {:mode :live})
        isolated (transport/start! "isolated" {:mode :live})
        client (connect boundary)
        submit (wire-command "same" 3 "assignment/submitted" {:assignment-id "a" :task "x"})]
    (try
      (is (:ok (exchange! client submit)))
      (is (:ok (exchange! client submit)))
      (is (= 1 (:observations (transport/snapshot boundary))))
      (is (= 0 (:observations (transport/snapshot isolated))))
      (.close ^Socket (:socket client))
      (is (= 0 (:connections (await-disconnect boundary))))
      (is (= :not-connected
             (try (transport/send! boundary (command "status" 5 :runtime/status {}))
                  (catch clojure.lang.ExceptionInfo error (:status (ex-data error))))))
      (is (empty? (events (policy/derive-controls session-id
                           (policy/normalize-commands session-id (transport/observations boundary))))))
      (is (thrown? Exception
                   (transport/send! isolated (command "feedback" 0 :runtime/status {}))))
      (finally
        (.close ^Socket (:socket client))
        (transport/close! boundary)
        (transport/close! isolated)))))

(deftest idle-client-stays-connected-for-later-feedback
  (let [observed (promise)
        boundary (transport/start! session-id
                                   {:mode :live :read-timeout-ms 50
                                    :on-observation (fn [_] (deliver observed :published))})
        client (connect boundary)]
    (try
      (is (= "received" (:status (exchange! client (wire-command "one" 0 "assignment/submitted"
                                                               {:assignment-id "a" :task "x"})))))
      (is (= :published (deref observed 1000 :timeout)))
      (Thread/sleep 200)
      (is (= 1 (:connections (transport/snapshot boundary))))
      (is (empty? (:failures (transport/snapshot boundary))))
      (let [feedback (policy/describe-commands
                      session-id (policy/normalize-commands session-id (transport/observations boundary)))]
        (is (= 1 (transport/send! boundary (first (events feedback)))))
        (is (= "user/command-accepted"
               (:kind (json/read-json (.readLine ^BufferedReader (:reader client)))))))
      (finally (.close ^Socket (:socket client)) (transport/close! boundary)))))

(deftest incomplete-frame-still-times-out-and-disconnects
  (let [boundary (transport/start! session-id {:mode :live :read-timeout-ms 50})
        client (connect boundary)]
    (try
      (is (= "received" (:status (exchange! client (wire-command "one" 0 "assignment/submitted"
                                                               {:assignment-id "a" :task "x"})))))
      (.write ^OutputStreamWriter (:writer client) "{")
      (.flush ^OutputStreamWriter (:writer client))
      (let [state (await-disconnect boundary)]
        (is (zero? (:connections state)))
        (is (= [:read-timeout] (:failures state)))
        (is (= 1 (:observations state))))
      (finally (.close ^Socket (:socket client)) (transport/close! boundary)))))

(deftest framing-receipt-precedes-synchronous-domain-feedback
  (let [owner (atom nil)
        boundary (transport/start!
                  session-id
                  {:mode :live
                   :on-observation
                   (fn [log]
                     (let [feedback (policy/describe-commands session-id
                                      (policy/normalize-commands session-id log))]
                       (transport/send! @owner (first (events feedback)))))})
        client (connect boundary)]
    (reset! owner boundary)
    (try
      (is (= "received"
             (:status (exchange! client (wire-command "one" 0 "assignment/submitted"
                                                      {:assignment-id "a" :task "x"})))))
      (is (= "user/command-accepted"
             (:kind (json/read-json (.readLine ^BufferedReader (:reader client))))))
      (finally (.close ^Socket (:socket client)) (transport/close! boundary)))))

(deftest publication-errors-are-distinct-and-feedback-respects-the-configured-bound
  (let [boundary (transport/start! session-id
                                  {:mode :live :max-frame-bytes 128
                                   :on-observation (fn [_] (throw (ex-info "fixture" {})))})
        client (connect boundary)]
    (try
      (is (= "received"
             (:status (exchange! client (wire-command "one" 0 "assignment/submitted"
                                                      {:assignment-id "a" :task "x"})))))
      (is (= "publication"
             (get-in (json/read-json (.readLine ^BufferedReader (:reader client)))
                     [:error :category])))
      (is (= 1 (:observations (transport/snapshot boundary))))
      (is (thrown? Exception
                   (transport/send! boundary
                                    (command "large" 1 :runtime/status
                                             {:message (apply str (repeat 128 "x"))}))))
      (finally (.close ^Socket (:socket client)) (transport/close! boundary)))))

(deftest observation-and-replay-do-not-create-sockets-or-send
  (doseq [mode [:observe :replay]]
    (let [boundary (transport/start! session-id {:mode mode})]
      (is (nil? (transport/port boundary)))
      (is (zero? (:connections (transport/snapshot boundary))))
      (is (thrown? Exception (transport/send! boundary (command "x" 0 :runtime/status {}))))
      (is (:closed? (transport/close! boundary))))))

(deftest frame-and-session-event-bounds-are-enforced-before-publication
  (let [boundary (transport/start! session-id {:mode :live :max-frame-bytes 128 :max-events 1})
        client (connect boundary)]
    (try
      (is (= "frame-too-large"
             (get-in (raw-line! client (apply str (repeat 256 "x"))) [:error :reason])))
      (is (zero? (:observations (transport/snapshot boundary))))
      (finally (.close ^Socket (:socket client)) (transport/close! boundary))))
  (let [boundary (transport/start! session-id {:mode :live :max-events 1})
        client (connect boundary)]
    (try
      (is (:ok (exchange! client (wire-command "one" 0 "assignment/submitted" {:assignment-id "a" :task "x"}))))
      (is (= "session-event-limit"
             (get-in (exchange! client (wire-command "two" 1 "assignment/cancel-requested" {:assignment-id "a"}))
                     [:error :reason])))
      (is (= 1 (:observations (transport/snapshot boundary))))
      (transport/close! boundary)
      (is (nil? (.readLine ^BufferedReader (:reader client))))
      (finally (.close ^Socket (:socket client)) (transport/close! boundary)))))
