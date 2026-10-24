(ns graph.lain-editor.protected-server
  "Authenticated loopback runtime for an external sign-in gateway."
  (:require [clojure.java.io :as io]
            [graph.lain-editor.server :as server])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn configuration [environment]
  (let [host (get environment "LAIN_RUNTIME_HOST")
        port (get environment "LAIN_RUNTIME_PORT")
        root (get environment "LAIN_RUNTIME_PROJECT_ROOT")
        credential-file (get environment "LAIN_RUNTIME_BEARER_CREDENTIAL_FILE")]
    (when-not (and (= "127.0.0.1" host) (string? port) (re-matches #"[0-9]+" port)
                   (string? root) (.isAbsolute (io/file root))
                   (.isDirectory (io/file root))
                   (string? credential-file) (.isFile (io/file credential-file)))
      (throw (ex-info "Protected runtime requires explicit loopback, port, project root and credential file" {})))
    (let [credential (.trim ^String (slurp credential-file))
          port (Long/parseLong port)]
      (when-not (and (<= 0 port 65535) (re-matches #"[A-Za-z0-9_-]{32,}" credential))
        (throw (ex-info "Invalid protected runtime configuration" {})))
      {:port port :project-root (.getCanonicalPath (io/file root)) :credential credential})))

(defn authorized? [credential exchange]
  (let [values (.get (.getRequestHeaders exchange) "Authorization")]
    (boolean
     (and (= 1 (count values))
          (MessageDigest/isEqual
           (.getBytes (str "Bearer " credential) StandardCharsets/UTF_8)
           (.getBytes ^String (first values) StandardCharsets/UTF_8))))))

(defn start! [{:keys [port project-root credential]}]
  (server/start! port {:project-root project-root
                      :authorize? (partial authorized? credential)}))

(defn -main [& _]
  (let [runtime (start! (configuration (System/getenv)))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (:close runtime)))
    (println (str "Protected Lain runtime: http://127.0.0.1:" (:port runtime)))
    @(promise)))
