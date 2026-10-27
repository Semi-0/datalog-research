(ns examples.lain.subagents.socket-transport
  "Owned application socket boundary. This is not the editor's instance API."
  (:require [clojure.string :as str]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.socket-extension :as policy]
            [graph.json :as json])
  (:import [java.io ByteArrayOutputStream InputStream OutputStreamWriter]
           [java.net InetAddress ServerSocket Socket SocketException SocketTimeoutException]
           [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]))

(defrecord SocketBoundary [session mode server state])

(defn- wire-error [reason]
  (ex-info "Invalid application socket frame" {:category :wire :reason reason}))

(defn- nonempty-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn decode-command
  "Decode a wire envelope; leave command-specific validation to the pure policy."
  [session-id text]
  (let [command (try (json/read-json text)
                     (catch Exception _ (throw (wire-error :invalid-json))))]
    (when-not (map? command) (throw (wire-error :invalid-envelope)))
    (when-not (= session-id (:session command)) (throw (wire-error :foreign-session)))
    (when-not (and (nonempty-string? (:id command)) (<= (count (:id command)) 256))
      (throw (wire-error :invalid-command-id)))
    (when-not (and (integer? (:order command)) (<= 0 (:order command) Long/MAX_VALUE))
      (throw (wire-error :invalid-command-order)))
    (when-not (and (nonempty-string? (:kind command)) (<= (count (:kind command)) 128))
      (throw (wire-error :invalid-command-kind)))
    (facts/fact session-id (:id command) (:order command) :socket/command
                {:command-kind (:kind command)
                 :command (apply dissoc command policy/envelope-keys)})))

(defn- read-frame [^InputStream input max-bytes]
  (let [buffer (ByteArrayOutputStream.)]
    (loop []
      (let [byte (.read input)]
        (cond
          (= -1 byte)
          (if (zero? (.size buffer)) nil (throw (wire-error :unterminated-frame)))
          (= 10 byte)
          (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                          (.onMalformedInput CodingErrorAction/REPORT)
                          (.onUnmappableCharacter CodingErrorAction/REPORT))]
            (try
              (str/replace (str (.decode decoder (ByteBuffer/wrap (.toByteArray buffer)))) #"\r$" "")
              (catch java.nio.charset.CharacterCodingException _
                (throw (wire-error :invalid-utf8)))))
          (>= (.size buffer) max-bytes) (throw (wire-error :frame-too-large))
          :else (do (.write buffer byte) (recur)))))))

(defn- write-line! [^OutputStreamWriter writer value]
  (locking writer
    (.write writer (json/write-json value))
    (.write writer "\n")
    (.flush writer)))

(defn- record-failure! [state reason]
  (swap! state update :failures #(vec (take-last 32 (conj % reason)))))

(defn- record-command! [boundary event max-events]
  (let [state (:state boundary)]
    (locking state
      (when (:closed? @state) (throw (wire-error :closed)))
      (let [updated (facts/accumulate (:observations @state) [event])]
        (when (> (count (facts/ordered-facts updated)) max-events)
          (throw (wire-error :session-event-limit)))
        (swap! state assoc :observations updated)
        updated))))

(defn- serve-client! [boundary id ^Socket socket on-observation options]
  (try
    (with-open [socket socket
                reader (.getInputStream socket)
                writer (OutputStreamWriter. (.getOutputStream socket) StandardCharsets/UTF_8)]
      (swap! (:state boundary) assoc-in [:clients id :writer] writer)
      (loop []
        (when-not (:closed? @(:state boundary))
          (let [outcome
                (try
                  (if-let [frame (read-frame reader (:max-frame-bytes options))]
                    (let [event (decode-command (:session boundary) frame)
                          log (record-command! boundary event (:max-events options))]
                      ;; Receipt acknowledges framing/retention before any domain
                      ;; feedback; a rejected command can still have this receipt.
                      (write-line! writer {:ok true :status :received :id (:id event)})
                      (try
                        (on-observation log)
                        (catch Exception error
                          (throw (ex-info "Application command publication failed"
                                          {:category :publication :reason :publication-failed} error))))
                      :continue)
                    :eof)
                  (catch clojure.lang.ExceptionInfo error
                    (let [data (ex-data error)
                          reason (or (:reason data) :fact-conflict)]
                      (write-line! writer {:ok false :error {:category (or (:category data) :wire)
                                                           :reason reason}})
                      (record-failure! (:state boundary) reason)
                      (if (contains? #{:frame-too-large :unterminated-frame :closed} reason)
                        :stop :continue))))]
            (when (= :continue outcome) (recur))))))
    (catch SocketTimeoutException _ (record-failure! (:state boundary) :read-timeout))
    (catch SocketException _ nil)
    (catch Exception _ (record-failure! (:state boundary) :connection-failed))
    (finally (swap! (:state boundary) update :clients dissoc id))))

(defn- accept-client! [boundary ^Socket socket on-observation options]
  (let [state (:state boundary)
        id (str (random-uuid))
        thread (doto (Thread. #(serve-client! boundary id socket on-observation options)
                             "lain-user-socket-client")
                 (.setDaemon true))
        accepted? (locking state
                    (if (or (:closed? @state)
                            (>= (count (:clients @state)) (:max-clients options)))
                      false
                      (do
                        (.setSoTimeout socket (:read-timeout-ms options))
                        (swap! state assoc-in [:clients id] {:socket socket :thread thread})
                        true)))]
    (if accepted? (.start thread) (.close socket))))

(defn start!
  "Default observation mode performs no I/O. Live mode binds loopback only."
  ([session-id] (start! session-id {}))
  ([session-id {:keys [mode port on-observation] :or {mode :observe port 0 on-observation (fn [_])}
                :as supplied}]
   (facts/empty-log session-id)
   (when (and (contains? supplied :host) (not= "127.0.0.1" (:host supplied)))
     (throw (ex-info "Application socket boundary only supports loopback" {})))
   (when-not (contains? #{:live :observe :replay} mode)
     (throw (ex-info "Unsupported socket boundary mode" {:mode mode})))
   (when-not (and (integer? port) (<= 0 port 65535) (ifn? on-observation))
     (throw (ex-info "Invalid socket boundary configuration" {})))
   (let [options (merge {:max-frame-bytes 32768 :max-events 4096
                         :max-clients 8 :read-timeout-ms 5000}
                        (select-keys supplied [:max-frame-bytes :max-events :max-clients :read-timeout-ms]))]
     (doseq [[key value] options]
       (when-not (and (integer? value) (pos? value) (<= value Integer/MAX_VALUE))
         (throw (ex-info "Invalid socket boundary bound" {:option key}))))
     (when (> (:max-frame-bytes options) 1048576)
       (throw (ex-info "Socket frame bound exceeds maximum" {:maximum 1048576})))
     (let [server (when (= :live mode)
                    (ServerSocket. (int port) 16 (InetAddress/getByName "127.0.0.1")))
           state (atom {:closed? false :clients {} :failures [] :options options
                        :observations (facts/empty-log session-id)})
           boundary (->SocketBoundary session-id mode server state)]
       (when server
         (let [thread (doto
                       (Thread.
                        (fn []
                          (try
                            (while (not (:closed? @state))
                              (accept-client! boundary (.accept ^ServerSocket server)
                                              on-observation options))
                            (catch SocketException _
                              (when-not (:closed? @state)
                                (record-failure! state :listener-failed)))))
                        "lain-user-socket-accept")
                       (.setDaemon true))]
           (swap! state assoc :accept-thread thread)
           (.start thread)))
       boundary))))

(defn port [boundary]
  (when-let [^ServerSocket server (:server boundary)] (.getLocalPort server)))

(defn observations [boundary] (:observations @(:state boundary)))

(defn snapshot [boundary]
  (let [state @(:state boundary)]
    {:session (:session boundary) :mode (:mode boundary) :port (port boundary)
     :closed? (:closed? state) :connections (count (:clients state))
     :observations (count (facts/ordered-facts (:observations state)))
     :failures (:failures state)}))

(defn send!
  "Write one already-projected feedback event. Effect claims/receipts are external."
  [boundary payload]
  (when-not (= :live (:mode boundary))
    (throw (ex-info "Observation/replay socket boundaries cannot send" {:mode (:mode boundary)})))
  (when (:closed? @(:state boundary)) (throw (ex-info "Socket boundary is closed" {})))
  ;; Reuse the pure encoder's bounds and graph rejection at the last I/O boundary.
  (let [encoded (policy/encode-feedback (:session boundary)
                                       (facts/from-facts (:session boundary) [payload]))
        payload (:payload (first (facts/ordered-facts encoded)))
        bytes (alength (.getBytes ^String (json/write-json payload) StandardCharsets/UTF_8))
        limit (get-in @(:state boundary) [:options :max-frame-bytes])
        clients (filterv :writer (vals (:clients @(:state boundary))))]
    (when (> bytes limit)
      (throw (ex-info "Socket feedback exceeds configured frame limit" {:limit limit})))
    (when (empty? clients)
      (throw (ex-info "No application socket client is connected" {:status :not-connected})))
    (reduce (fn [sent {:keys [socket writer]}]
              (try
                (write-line! writer payload)
                (inc sent)
                (catch Exception error
                  (.close ^Socket socket)
                  (throw (ex-info "Application socket write may be partial"
                                  {:status :uncertain :written sent} error)))))
            0 clients)))

(defn close! [boundary]
  (let [state (:state boundary)
        {:keys [clients accept-thread]}
        (locking state
          (swap! state assoc :closed? true)
          @state)]
    (when-let [^ServerSocket server (:server boundary)] (.close server))
    (doseq [{:keys [socket]} (vals clients)] (.close ^Socket socket))
    (doseq [^Thread thread (cons accept-thread (map :thread (vals clients)))
            :when (and thread (not (identical? thread (Thread/currentThread))))]
      (.join thread))
    (snapshot boundary)))
