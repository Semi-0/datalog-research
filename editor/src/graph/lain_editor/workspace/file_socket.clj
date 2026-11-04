(ns graph.lain-editor.workspace.file-socket
  "Local JSON socket for file investigation and explicitly injected editor bridges."
  (:require [clojure.string :as str]
            [graph.json :as json]
            [graph.lain-editor.workspace.file-service :as files])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress ConnectException]
           [java.nio.channels ServerSocketChannel SocketChannel Channels]
           [java.nio.file Files Paths LinkOption]
           [java.nio.file.attribute PosixFilePermissions BasicFileAttributes]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors ThreadFactory]))

(defn dispatch! [service bridge request]
  (let [op (:op request)]
    (cond
      (and (string? op) (str/starts-with? op "files/")) (files/command! service request)
      (and bridge (string? op) (str/starts-with? op "editor/")) (bridge request)
      :else (throw (ex-info "Unknown file/socket operation" {:op op})))))

(defn- read-frame! [^java.io.Reader reader]
  (loop [buffer (StringBuilder.)]
    (let [character (.read reader)]
      (cond
        (= -1 character) (when (pos? (.length buffer)) (str buffer))
        (= 10 character) (str buffer)
        (>= (.length buffer) files/max-bytes) (throw (ex-info "Socket request exceeds the size limit" {}))
        :else (recur (.append buffer (char character)))))))

(defn- respond [service bridge text]
  (try
    (let [request (json/read-json text)]
      (when-not (map? request) (throw (ex-info "Expected a JSON request object" {})))
      (try {:id (:id request) :ok true :result (dispatch! service bridge request)}
           (catch Exception error
             {:id (:id request) :ok false :error {:message (ex-message error) :data (ex-data error)}})))
    (catch Exception error {:ok false :error {:message (ex-message error)}})))

(defn- release-stale-socket! [path]
  (when (Files/exists path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
    (let [attributes (Files/readAttributes path BasicFileAttributes (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
          owner (Files/getOwner path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))]
      (when-not (and (.isOther attributes) (= (.getName owner) (System/getProperty "user.name")))
        (throw (ex-info "Socket path is occupied by a different file or owner" {})))
      (try
        (with-open [probe (SocketChannel/open StandardProtocolFamily/UNIX)]
          (.connect probe (UnixDomainSocketAddress/of path)))
        (throw (ex-info "File socket is already active" {}))
        (catch ConnectException _
          (when (Files/exists path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
            (let [current (Files/readAttributes path BasicFileAttributes (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))]
              (when-not (= (.fileKey attributes) (.fileKey current))
                (throw (ex-info "Socket path changed during startup" {})))
              (Files/delete path))))))))

(defn start! [{:keys [path service bridge]}]
  (let [socket-path (.toAbsolutePath (Paths/get path (make-array String 0)))
        parent (.getParent socket-path)]
    (when-not (Files/isDirectory parent (make-array LinkOption 0))
      (throw (ex-info "Socket parent directory must already exist" {})))
    (release-stale-socket! socket-path)
    (let [server (ServerSocketChannel/open StandardProtocolFamily/UNIX)
          clients (atom #{}) closed? (atom false)
          executor (Executors/newFixedThreadPool 4
                                                 (reify ThreadFactory
                                                   (newThread [_ task] (doto (Thread. task "lain-file-socket-client") (.setDaemon true)))))]
      (try
        (.bind server (UnixDomainSocketAddress/of socket-path))
        (Files/setPosixFilePermissions socket-path (PosixFilePermissions/fromString "rw-------"))
        (let [attributes (Files/readAttributes socket-path BasicFileAttributes (make-array LinkOption 0))
              file-key (.fileKey attributes)
              acceptor (Thread.
                        ^Runnable
                        (fn []
                          (while (not @closed?)
                            (try
                              (let [client (.accept server)]
                                (if (>= (count @clients) 16)
                                  (.close client)
                                  (do
                                    (swap! clients conj client)
                                    (.submit executor
                                             ^Runnable
                                             (fn []
                                               (try
                                                 (with-open [channel client
                                                             reader (Channels/newReader channel (.newDecoder StandardCharsets/UTF_8) -1)
                                                             writer (Channels/newWriter channel (.newEncoder StandardCharsets/UTF_8) -1)]
                                                   (loop []
                                                     (when-let [frame (read-frame! reader)]
                                                       (.write writer (str (json/write-json (respond service bridge frame)) "\n"))
                                                       (.flush writer)
                                                       (recur))))
                                                 (catch Exception _ nil)
                                                 (finally (swap! clients disj client))))))))
                              (catch Exception _
                                (when-not @closed? (reset! closed? true))))))
                        "lain-file-socket-accept")]
          (.setDaemon acceptor true)
          (.start acceptor)
          {:path (str socket-path)
           :close (fn []
                    (reset! closed? true)
                    (.close server)
                    (doseq [^SocketChannel client @clients] (.close client))
                    (.shutdownNow executor)
                    (when (Files/exists socket-path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                      (let [current (Files/readAttributes socket-path BasicFileAttributes (make-array LinkOption 0))]
                        (when (= file-key (.fileKey current)) (Files/deleteIfExists socket-path)))))})
        (catch Exception error (.close server) (.shutdownNow executor) (throw error))))))
