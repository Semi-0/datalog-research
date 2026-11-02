(ns deploy.session-sandbox.runtime
  "Sandbox-local HTTP adapter. The owner lives independently of all editor views."
  (:require [clojure.string :as str]
            [graph.json :as json]
            [graph.lain-editor.inspection.core :as inspection]
            [graph.lain-editor.layout.graph-layout :as layout]
            [graph.lain-editor.runtime.display :as display]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.server :as server]
            [propagators.compiler-2.session :as session]
            [propagators.compiler-2.session.files :as files]
            [propagators.compiler-2.session.host :as host]
            [propagators.stdlib.source-publication :as publication])
  (:import (com.sun.net.httpserver HttpServer)
           (java.net InetSocketAddress)
           (java.nio.channels Channels)
           (java.nio.file Files LinkOption OpenOption Paths StandardOpenOption FileAlreadyExistsException)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util UUID)
           (java.util.concurrent Executors)))

(def max-file-bytes 262144)
;; Total client-supplied code, including uploads and successive declarations.
;; This also bounds duplicate source views and escaping in a 16 MiB export.
(def max-code-bytes 524288)
(def max-body-bytes (* 2 1024 1024))
(def max-receipts 128)
(def no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(deftype SessionOwner [prepared state layouts receipts interpreter])

(defn create-owner []
  (let [entry (editor/prepare-compiler "(define sandbox:ready true)" [])
        boundary (host/boundary-for (:session entry))]
    (SessionOwner. entry
                   (atom {:generation 0 :source "" :started? false :views #{} :uploads #{}
                          :extensions [] :code-bytes 0 :inputs {}})
                   (atom {}) (atom {}) (host/effect-interpreter boundary))))

(defn prepared [^SessionOwner owner] (.-prepared owner))
(defn owner-state [^SessionOwner owner] (.-state owner))
(defn owner-layouts [^SessionOwner owner] (.-layouts owner))
(defn owner-receipts [^SessionOwner owner] (.-receipts owner))
(defn owner-interpreter [^SessionOwner owner] (.-interpreter owner))
(defn boundary [owner] (host/boundary-for (:session (prepared owner))))
(defn current [owner] (host/current-session (boundary owner)))
(defn close-owner! [owner] (host/close! (boundary owner)))

(defn error-data [error]
  {:message (subs (str (.getMessage ^Throwable error)) 0 (min 2000 (count (str (.getMessage ^Throwable error)))))
   :class (.getName (class error))})

(defn identity-view [owner]
  (into {} (map (fn [[key value]] [key (str value)])) (session/session-identity (current owner))))

(defn owner-status [owner]
  (let [state @(owner-state owner)]
    {:sessionId (:id (identity-view owner)) :identity (identity-view owner)
     :generation (:generation state) :source (:source state)
     :extensions (:extensions state) :views (vec (sort (:views state)))
     :bindings (mapv str (sort-by str (keys (:primitives (session/session-declarations (current owner))))))
     :displayMode (display/mode (:program/net @(-> owner prepared :session)))
     :codeBytes (:code-bytes state) :codeLimit max-code-bytes
     :postCommitError (:postCommitError state)
     :failures (mapv error-data (take-last 16 (host/failures (boundary owner))))}))

(defn require-generation! [owner command]
  (when-not (= (:generation @(owner-state owner)) (:generation command))
    (throw (ex-info "Stale or missing generation" {:generation (:generation @(owner-state owner))}))))

(defn pane-name [command]
  (let [pane (:pane command)]
    (when-not (and (string? pane) (re-matches #"[A-Za-z0-9_.-]{1,64}" pane))
      (throw (ex-info "Invalid pane" {})))
    pane))

(defn flat-file [name]
  (when-not (and (string? name) (re-matches #"[a-z][a-z0-9_]{0,59}\.(clj|lain)" name))
    (throw (ex-info "Expected a flat .clj or .lain file name" {})))
  (Paths/get "/workspace" (into-array String [name])))

(defn read-path [path limit]
  (with-open [channel (Files/newByteChannel path (into-array OpenOption [StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS]))
              input (Channels/newInputStream channel)]
    (let [bytes (.readNBytes input (inc limit))]
      (when (> (alength bytes) limit)
        (throw (ex-info "File exceeds export limit" {})))
      (String. bytes StandardCharsets/UTF_8))))

(defn require-code-budget! [owner additional]
  (when (> (+ (:code-bytes @(owner-state owner)) additional) max-code-bytes)
    (throw (ex-info "Session code budget exceeded; export and start another sandbox"
                    {:limit max-code-bytes}))))

(defn upload! [owner {:keys [name content]}]
  (let [path (flat-file name)]
    (when-not (string? content) (throw (ex-info "File content must be text" {})))
    (let [bytes (.getBytes ^String content StandardCharsets/UTF_8)]
      (when (> (alength bytes) max-file-bytes) (throw (ex-info "File too large" {})))
      (when-not (contains? (:uploads @(owner-state owner)) name)
        (require-code-budget! owner (alength bytes)))
      (when (>= (count (:uploads @(owner-state owner))) 32)
        (when-not (contains? (:uploads @(owner-state owner)) name)
          (throw (ex-info "Upload catalog is full" {}))))
      (try
        (with-open [channel (Files/newByteChannel path (into-array OpenOption [StandardOpenOption/WRITE StandardOpenOption/CREATE_NEW LinkOption/NOFOLLOW_LINKS]))
                    output (Channels/newOutputStream channel)]
          (.write output bytes))
        (catch FileAlreadyExistsException _
          (when-not (= content (read-path path max-file-bytes))
            (throw (ex-info "Immutable file already has different content; use a new module name" {:name name}))))))
    (swap! (owner-state owner)
           (fn [state]
             (if (contains? (:uploads state) name)
               state
               (-> state (update :uploads conj name)
                   (update :code-bytes + (alength (.getBytes ^String content StandardCharsets/UTF_8)))))))
    {:name name :generation (:generation @(owner-state owner))}))

(defn publish-source!
  ([owner source label] (publish-source! owner source label {}))
  ([owner source label factory-arguments]
  (when-not (and (string? source) (<= (count (.getBytes ^String source StandardCharsets/UTF_8)) max-file-bytes))
    (throw (ex-info "Source must be bounded text" {})))
  (require-code-budget! owner (alength (.getBytes ^String source StandardCharsets/UTF_8)))
  (let [before (session/session-declarations (current owner))
        filename (str "turn_" (str/replace (str (UUID/randomUUID)) "-" "") ".lain")
        extension (files/prepare-extension! {:id filename :root "/workspace" :files {filename source}
                                             :factory-arguments factory-arguments})
        outcome (try (host/extend! (boundary owner) extension) nil
                     (catch Exception error error))
        committed? (not= before (session/session-declarations (current owner)))]
    (when (and outcome (not committed?)) (throw outcome))
    (swap! (owner-state owner)
           (fn [state]
             (cond-> (-> state (dissoc :postCommitError) (update :generation inc)
                          (update :code-bytes + (alength (.getBytes ^String source StandardCharsets/UTF_8)))
                          (update :extensions conj {:label label :file filename :source source}))
               outcome (assoc :postCommitError (error-data outcome)))))
    (cond-> (owner-status owner) outcome (assoc :postCommitError (error-data outcome))))))

(defn run-source! [owner {:keys [source]}]
  (let [state @(owner-state owner)]
    (if (:started? state)
      (throw (ex-info "This owner already has a program. Extend it, or explicitly use sandbox-rerun" {}))
      (let [result (publish-source! owner source "initial program")]
        (swap! (owner-state owner) assoc :started? true :source source)
        (assoc result :source source)))))

(defn install! [owner {:keys [name entry revision injectEffects]}]
  (flat-file name)
  (when-not (or (nil? injectEffects) (boolean? injectEffects))
    (throw (ex-info "injectEffects must be an explicit boolean" {})))
  (when-not (and (str/ends-with? name ".clj") (string? entry)
                 (re-matches #"[a-z][a-z0-9-]*/[a-z][a-z0-9?!-]*" entry)
                 (= (namespace (keyword entry)) (str/replace (subs name 0 (- (count name) 4)) "_" "-"))
                 (integer? revision) (<= 0 revision 1000000000)
                 (contains? (:uploads @(owner-state owner)) name))
    (throw (ex-info "Native entry must match an uploaded, versioned module and an explicit revision" {})))
  ;; Native evaluation is deliberately inside the OS sandbox, after the generation
  ;; fence, and before the compiler's atomic declaration publication.
  (read-path (flat-file name) max-file-bytes)
  (publish-source! owner
                   (str "(load-primitive-environment " (pr-str name) " " (str (keyword entry)) " " revision ")")
                   name (if injectEffects {(keyword entry) [(owner-interpreter owner)]} {})))

(defn inspect-owner [owner command]
  (require-generation! owner command)
  (let [pane (pane-name command)
        view (assoc (prepared owner) :generation (:generation @(owner-state owner)))]
    (inspection/inspect (atom {pane view}) command)))

(defn export-owner [owner]
  {:status (owner-status owner)
   :files (into {} (map (fn [name] [name (read-path (flat-file name) max-file-bytes)])) (:uploads @(owner-state owner)))
   :declarations (session/source-files (current owner))
   :receipts @(owner-receipts owner)
   :log (try (read-path (Paths/get "/workspace/runtime.log" (make-array String 0)) 65536)
             (catch Exception error {:unavailable (error-data error)}))})

(defn input! [owner {:keys [cell revision value]}]
  (when-not (and (string? cell) (<= 1 (count cell) 128) (not (re-find #"\s" cell))
                 (integer? revision) (<= 0 revision 1000000000))
    (throw (ex-info "Input requires a binding name, bounded revision and value" {})))
  (when (<= revision (get-in @(owner-state owner) [:inputs cell] -1))
    (throw (ex-info "Input revision must increase" {:cell cell})))
  (let [target (session/binding-cell (current owner) (symbol cell))
        result (host/submit! (boundary owner)
                            (session/cell-input target (publication/active [:sandbox cell] revision value)))]
    (when-not (= :accepted result)
      (throw (ex-info "Input was not admitted" {:status result})))
    ;; Admission is observable even if a later propagation fails. Do not report
    ;; that a queued mutation rolled back or authorize blind replay.
    (swap! (owner-state owner) #(-> % (assoc-in [:inputs cell] revision)
                                    (update :generation inc) (dissoc :postCommitError)))
    (try (host/step! (boundary owner))
         (catch Exception error
           (swap! (owner-state owner) assoc :postCommitError (error-data error))))
    (owner-status owner)))

(defn receipt-result [result]
  (if (map? result)
    (select-keys result [:sessionId :identity :generation :name :pane :postCommitError])
    {}))

(defn mutate! [owner command]
  (require-generation! owner command)
  (case (:op command)
    "sandbox-upload" (upload! owner command)
    "run" (run-source! owner command)
    "extend" (publish-source! owner (:source command) "extension")
    "sandbox-install" (install! owner command)
    "sandbox-input" (input! owner command)
    "sandbox-rerun" (do (host/rerun! (boundary owner) (str (UUID/randomUUID)))
                         (swap! (owner-state owner) #(-> % (update :generation inc) (assoc :inputs {}) (dissoc :postCommitError)))
                         (owner-status owner))
    "reopen-session" (let [pane (pane-name command)]
                         (when-not (= (:sessionId command) (:sessionId (owner-status owner)))
                           (throw (ex-info "Session identity does not match this sandbox" {})))
                         (when (>= (count (:views @(owner-state owner))) 16)
                           (throw (ex-info "Too many view leases" {})))
                         (swap! (owner-state owner) update :views conj pane)
                         (assoc (owner-status owner) :pane pane))
    "detach-session" (do (swap! (owner-state owner) update :views disj (pane-name command)) (owner-status owner))
    "close" (do (swap! (owner-state owner) update :views disj (pane-name command)) (owner-status owner))
    "restore" (do (when-not (= (:source command) (:source @(owner-state owner)))
                      (throw (ex-info "Refresh cannot replay or replace source. Reopen this owner instead" {})))
                    (swap! (owner-state owner) update :views conj (pane-name command))
                    (owner-status owner))
    "layout" (layout/compute! (owner-layouts owner) command)
    (throw (ex-info "Unsupported sandbox mutation" {:op (:op command)}))))

(def read-ops #{"sandbox-status" "list-sessions" "inspect" "sandbox-export" "sandbox-receipt"})

(defn execute! [owner command]
  (locking (owner-state owner)
   (case (:op command)
    "sandbox-status" (owner-status owner)
    "list-sessions" {:sessions [(assoc (owner-status owner) :session-id (:sessionId (owner-status owner)) :sourceBytes (count (:source @(owner-state owner))))]}
    "inspect" (inspect-owner owner command)
    "sandbox-export" (export-owner owner)
    "sandbox-receipt" (get @(owner-receipts owner) (:requestId command) {:status "unknown"})
    (let [id (:requestId command)]
      (when-not (and (string? id) (re-matches #"[0-9a-f-]{36}" id)) (throw (ex-info "Mutation requires a requestId" {})))
      (locking (owner-state owner)
        (when (contains? @(owner-receipts owner) id) (throw (ex-info "Duplicate mutation: inspect its receipt, do not replay" {:requestId id})))
        (when (>= (count @(owner-receipts owner)) max-receipts) (throw (ex-info "Receipt capacity reached; export and stop" {})))
        (swap! (owner-receipts owner) assoc id {:status "started" :op (:op command)})
        (try
          (let [result (mutate! owner command)]
            (swap! (owner-receipts owner) assoc id {:status "committed" :op (:op command) :result (receipt-result result)})
            result)
          (catch Exception error
            (swap! (owner-receipts owner) assoc id {:status "failed" :op (:op command) :error (error-data error)})
            (throw error))))))))

(defn authorized? [exchange token]
  (let [values (.get (.getRequestHeaders exchange) "Authorization")
        actual (when (= 1 (count values)) (first values))]
    (and (string? actual)
         (MessageDigest/isEqual (.getBytes (str "Bearer " token) StandardCharsets/UTF_8)
                                (.getBytes ^String actual StandardCharsets/UTF_8)))))

(defn handle! [owner token exchange]
  (try
    (if-not (authorized? exchange token)
      (server/respond! exchange 401 "application/json" "{\"ok\":false,\"error\":\"Unauthorized\"}")
      (let [path (.getPath (.getRequestURI exchange))]
        (if (and (= path "/api") (= "POST" (.getRequestMethod exchange)))
          (let [bytes (.readNBytes (.getRequestBody exchange) (inc max-body-bytes))]
            (when (> (alength bytes) max-body-bytes) (throw (ex-info "Request too large" {})))
            (let [command (json/read-json (String. bytes StandardCharsets/UTF_8))
                  result (execute! owner command)]
              (server/respond! exchange 200 "application/json" (json/write-json {:ok true :result result}))))
          (server/respond! exchange 404 "application/json" "{\"ok\":false,\"error\":\"Not found\"}"))))
    (catch Exception error
      (server/respond! exchange 400 "application/json" (json/write-json {:ok false :error (error-data error) :status (owner-status owner)})))
    (finally (.close exchange))))

(defn -main [& [port]]
  (let [configuration (json/read-json (slurp "/run/credential"))
        owner (create-owner)
        http (HttpServer/create (InetSocketAddress. "127.0.0.1" (Integer/parseInt port)) 16)
        executor (Executors/newFixedThreadPool 8)]
    (.createContext http "/" (reify com.sun.net.httpserver.HttpHandler
                               (handle [_ exchange] (handle! owner (:token configuration) exchange))))
    (.setExecutor http executor)
    (.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (.stop http 0) (close-owner! owner) (.shutdownNow executor))))
    (.start http)
    (println "Sandbox compiler owner ready" (:sessionId (owner-status owner)))
    @(promise)))
