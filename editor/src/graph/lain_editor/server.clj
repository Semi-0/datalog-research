(ns graph.lain-editor.server
  "Loopback-only HTTP boundary for the tiled Lain editor."
  (:require [clojure.java.io :as io]
            [graph.json :as json]
            [graph.lain-editor.inspection.core :as inspection]
            [graph.lain-editor.completion :as completion]
            [graph.lain-editor.development.handler :as development-handler]
            [graph.lain-editor.development.protocol :as development-protocol]
            [graph.lain-editor.development.runtime :as development-runtime]
            [graph.lain-editor.layout.graph-layout :as graph-layout]
            [graph.lain-editor.workspace.source-files :as source-files]
            [graph.lain-editor.workspace.files :as workspace-files]
            [graph.lain-editor.workspace.project-files :as project-files]
            [graph.lain-editor.workspace.commands :as workspace-commands]
            [graph.lain-editor.runtime.session :as editor])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors]))

(def ^:dynamic *project-root* nil)

(defn project-root []
  (or *project-root* (System/getProperty "user.dir")))

(def assets
  {"/" ["index.html" "text/html"]
   "/compiled/app.js" ["compiled/app.js" "text/javascript"]
   "/compiled/app.js.map" ["compiled/app.js.map" "application/json"]
   "/style.css" ["style.css" "text/css"]
   "/editing.mjs" ["editing.mjs" "text/javascript"]})

(defn- read-session! [editors command operation]
  (if (and (:sessionId command) (nil? (:pane command)))
    (let [entry (editor/require-owner editors command)
          view (atom {"session-target" entry})]
      ;; Ephemeral read projection only: lifetime and generation remain catalog-owned.
      (operation view (assoc command :pane "session-target")))
    (operation editors command)))

(defn command!
  ([editors command]
   (let [{:keys [layouts developments owners]} (editor/service-state editors)]
     (command! editors layouts developments owners command)))
  ([editors layouts command]
   (let [{:keys [developments owners]} (editor/service-state editors)]
     (command! editors layouts developments owners command)))
  ([editors layouts developments command]
   (command! editors layouts developments (:owners (editor/service-state editors)) command))
  ([editors layouts developments detached command]
   (locking editors
    (let [state (editor/service-state editors)]
      (alter-meta! editors assoc :graph.lain-editor.runtime.session/service
                   (assoc state :owners detached :layouts layouts :developments developments))
      (alter-meta! detached assoc :graph.lain-editor.runtime.session/service
                   (assoc state :owners detached :layouts layouts :developments developments)))
    (binding [editor/*owners* detached]
     (let [id (:pane command)]
       (case (:op command)
         "layout" (do (editor/require-generation (editor/require-pane editors id) (:generation command))
                      (graph-layout/compute! layouts command))
         "open-session" (editor/open-session! editors command)
         "session-status" (editor/session-status editors command)
         "remove-extension" (editor/remove-extension! editors command)
         "run" (let [result (editor/run! editors command)]
                 (development-runtime/close-pane! developments id)
                 (graph-layout/close-pane! layouts id)
                 result)
         "extend" (let [result (editor/extend! editors command)] (graph-layout/close-pane! layouts id) result)
         "spawn-slot" (let [result (editor/spawn-slots! editors command)]
                        (graph-layout/close-pane! layouts id)
                        result)
         "restore" (let [result (editor/restore! editors (:workspace command) (or (:reconnect command) {}))]
                     (development-runtime/close-all! developments)
                     (graph-layout/close! layouts)
                     result)
         "close" (let [result (editor/remove! editors id)]
                   (development-runtime/close-pane! developments id)
                   (graph-layout/close-pane! layouts id)
                   result)
         "detach-session" (do
                            (editor/require-generation (editor/require-pane editors id) (:generation command))
                            (development-runtime/close-pane! developments id)
                            (graph-layout/close-pane! layouts id)
                            (editor/detach! editors detached command))
         "list-sessions" (editor/list-detached detached)
         "reopen-session" (editor/reopen! editors detached command)
         "stop-session" (let [views (keep (fn [[pane entry]]
                                            (when (= (:sessionId command) (:sessionId entry)) pane)) @editors)
                              result (editor/stop-session! editors command)]
                          (doseq [pane views]
                            (development-runtime/close-pane! developments pane)
                            (graph-layout/close-pane! layouts pane))
                          result)
         "inspect" (read-session! editors command inspection/inspect)
         "complete" (read-session! editors command completion/candidates)
         "browse-files" (project-files/browse! (project-root) command)
         "open-file" (source-files/open-block! (project-root) command)
         "open-workspace" (workspace-files/open-workspace! (project-root) command)
         "save-block" (source-files/save-block! (project-root) command)
         "save-workspace" (workspace-files/save-workspace! (project-root) command)
         (throw (ex-info "Unknown editor operation" {}))))))))

(defn respond! [exchange status content-type body]
  (let [bytes (.getBytes ^String body java.nio.charset.StandardCharsets/UTF_8)
        headers (.getResponseHeaders exchange)]
    (.set headers "Content-Type" (str content-type "; charset=utf-8"))
    (.set headers "Cache-Control" "no-store")
    (.set headers "X-Content-Type-Options" "nosniff")
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)] (.write out bytes))))

(defn api! [editors layouts developments commands detached exchange]
  (when-not (= "POST" (.getRequestMethod exchange))
    (throw (ex-info "API requires POST" {})))
  ;; Local files may be loaded explicitly by Lain. Browser requests are same-origin.
  (let [origin (.getFirst (.getRequestHeaders exchange) "Origin")
        host (.getFirst (.getRequestHeaders exchange) "Host")]
    (when (and origin (not= origin (str "http://" host)))
      (throw (ex-info "Cross-origin request rejected" {}))))
  (let [body (slurp (.getRequestBody exchange))
        command (json/read-json body)
        result (cond
                 (development-protocol/operations (:op command))
                 (development-handler/command! developments command)
                 (workspace-commands/operations (:op command))
                 (workspace-commands/command! commands (project-root) command)
                 :else (command! editors layouts developments detached command))]
    (respond! exchange 200 "application/json" (json/write-json {:ok true :result result}))))

(defn handle! [editors layouts developments commands detached exchange]
  (try
    (let [path (.getPath (.getRequestURI exchange))]
      (cond
        (= path "/api") (api! editors layouts developments commands detached exchange)
        (and (= "GET" (.getRequestMethod exchange)) (contains? assets path))
        (let [[file mime] (get assets path)]
          (respond! exchange 200 mime (slurp (io/resource (str "public/lain-editor/" file)))))
        :else (respond! exchange 404 "text/plain" "Not found")))
    (catch Exception error
      (respond! exchange 400 "application/json"
                (json/write-json {:ok false :error (ex-message error)})))
    (finally (.close exchange))))

(defn start!
  ([port] (start! port {}))
  ([port {:keys [authorize? project-root]}]
  (let [editors (atom {}) layouts (atom {}) developments (atom {}) commands (atom {}) detached (atom {})
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)
        executor (Executors/newCachedThreadPool)]
    (.createContext server "/" (reify HttpHandler
                                  (handle [_ exchange]
                                    (if (and authorize? (not (authorize? exchange)))
                                      (try
                                        (respond! exchange 401 "application/json"
                                                  (json/write-json {:ok false :error "Unauthorized"}))
                                        (finally (.close exchange)))
                                      (binding [*project-root* project-root]
                                        (handle! editors layouts developments commands detached exchange))))))
    (.setExecutor server executor)
    (.start server)
    {:port (.getPort (.getAddress server)) :editors editors :layouts layouts
     :developments developments :detached detached
     :close (fn [] (.stop server 0) (.shutdownNow executor) (reset! commands {})
              (locking editors
                (development-runtime/close-all! developments)
                (graph-layout/close! layouts)
                (editor/close! editors)
                (editor/close! detached)))})))

(defn -main [& args]
  (let [port (if (seq args) (Long/parseLong (first args)) 4780)
        server (start! port)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (:close server)))
    (println (str "Lain tiled editor: http://127.0.0.1:" (:port server)))
    @(promise)))
