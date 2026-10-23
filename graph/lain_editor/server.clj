(ns graph.lain-editor.server
  "Loopback-only HTTP boundary for the tiled Lain editor."
  (:require [clojure.java.io :as io]
            [graph.json :as json]
            [graph.lain-editor.inspection :as inspection]
            [graph.lain-editor.development-handler :as development-handler]
            [graph.lain-editor.development-protocol :as development-protocol]
            [graph.lain-editor.development-runtime :as development-runtime]
            [graph.lain-editor.graph-layout :as graph-layout]
            [graph.lain-editor.source-files :as source-files]
            [graph.lain-editor.workspace-files :as workspace-files]
            [graph.lain-editor.workspace-commands :as workspace-commands]
            [graph.lain-editor.runtime :as editor])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors]))

(def assets
  {"/" ["index.html" "text/html"]
   "/compiled/app.js" ["compiled/app.js" "text/javascript"]
   "/compiled/app.js.map" ["compiled/app.js.map" "application/json"]
   "/style.css" ["style.css" "text/css"]})

(defn command!
  ([editors command]
   (when (= "layout" (:op command)) (throw (ex-info "Layout registry required" {})))
   (command! editors (atom {}) (atom {}) command))
  ([editors layouts command]
   (command! editors layouts (atom {}) command))
  ([editors layouts developments command]
   (locking editors
     (let [id (:pane command)]
       (case (:op command)
         "layout" (do (editor/require-generation (editor/require-pane editors id) (:generation command))
                      (graph-layout/compute! layouts command))
         "run" (let [result (editor/run! editors command)]
                 (development-runtime/close-pane! developments id)
                 (graph-layout/close-pane! layouts id)
                 result)
         "extend" (let [result (editor/extend! editors command)] (graph-layout/close-pane! layouts id) result)
         "spawn-slot" (let [result (editor/spawn-slots! editors command)]
                        (graph-layout/close-pane! layouts id)
                        result)
         "restore" (let [result (editor/restore! editors (:workspace command))]
                     (development-runtime/close-all! developments)
                     (graph-layout/close! layouts)
                     result)
         "close" (let [result (editor/remove! editors id)]
                   (development-runtime/close-pane! developments id)
                   (graph-layout/close-pane! layouts id)
                   result)
         "inspect" (inspection/inspect editors command)
         "save-block" (source-files/save-block! (System/getProperty "user.dir") command)
         "save-workspace" (workspace-files/save-workspace! (System/getProperty "user.dir") command)
         (throw (ex-info "Unknown editor operation" {})))))))

(defn respond! [exchange status content-type body]
  (let [bytes (.getBytes ^String body java.nio.charset.StandardCharsets/UTF_8)
        headers (.getResponseHeaders exchange)]
    (.set headers "Content-Type" (str content-type "; charset=utf-8"))
    (.set headers "Cache-Control" "no-store")
    (.set headers "X-Content-Type-Options" "nosniff")
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)] (.write out bytes))))

(defn api! [editors layouts developments commands exchange]
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
                 (workspace-commands/command! commands (System/getProperty "user.dir") command)
                 :else (command! editors layouts developments command))]
    (respond! exchange 200 "application/json" (json/write-json {:ok true :result result}))))

(defn handle! [editors layouts developments commands exchange]
  (try
    (let [path (.getPath (.getRequestURI exchange))]
      (cond
        (= path "/api") (api! editors layouts developments commands exchange)
        (and (= "GET" (.getRequestMethod exchange)) (contains? assets path))
        (let [[file mime] (get assets path)]
          (respond! exchange 200 mime (slurp (io/resource (str "graph/lain_editor_static/" file)))))
        :else (respond! exchange 404 "text/plain" "Not found")))
    (catch Exception error
      (respond! exchange 400 "application/json"
                (json/write-json {:ok false :error (ex-message error)})))
    (finally (.close exchange))))

(defn start! [port]
  (let [editors (atom {}) layouts (atom {}) developments (atom {}) commands (atom {})
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)
        executor (Executors/newCachedThreadPool)]
    (.createContext server "/" (reify HttpHandler
                                  (handle [_ exchange] (handle! editors layouts developments commands exchange))))
    (.setExecutor server executor)
    (.start server)
    {:port (.getPort (.getAddress server)) :editors editors :layouts layouts
     :developments developments
     :close (fn [] (.stop server 0) (.shutdownNow executor) (reset! commands {})
              (development-runtime/close-all! developments)
              (graph-layout/close! layouts) (editor/close! editors))}))

(defn -main [& args]
  (let [port (if (seq args) (Long/parseLong (first args)) 4780)
        server (start! port)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (:close server)))
    (println (str "Lain tiled editor: http://127.0.0.1:" (:port server)))
    @(promise)))
