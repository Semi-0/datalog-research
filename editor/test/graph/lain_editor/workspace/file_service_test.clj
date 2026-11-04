(ns graph.lain-editor.workspace.file-service-test
  (:require [clojure.test :refer [deftest is]]
            [graph.json :as json]
            [graph.lain-editor.workspace.file-service :as files]
            [graph.lain-editor.workspace.file-socket :as socket])
  (:import [java.nio.file Files Path FileVisitOption]
           [java.nio.channels SocketChannel Channels]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.io BufferedReader]))

(defn with-root [test]
  (let [root (Files/createTempDirectory "lain-file-watch-" (make-array java.nio.file.attribute.FileAttribute 0))
        service (files/start! root)]
    (try (test root service)
         (finally
           ((:close service))
           (with-open [stream (Files/walk root (make-array FileVisitOption 0))]
             (doseq [^Path path (reverse (sort-by #(.getNameCount ^Path %) (iterator-seq (.iterator stream))))]
               (Files/deleteIfExists path)))))))

(deftest guarded-file-mutations-and-external-changes
  (with-root
    (fn [^Path root service]
      (let [saved (files/write-file! service {:path "nested/code.lain" :source "(define result 1)"})]
        (is (:exists saved))
        (is (string? (:version saved)))
        (is (thrown? Exception (files/write-file! service {:path "nested/code.lain" :source "overwrite"})))
        (let [appended (files/write-file! service {:path "nested/code.lain" :source "\n(display result)"
                                                   :baseVersion (:version saved) :append true})]
          (is (not= (:version saved) (:version appended)))
          (is (= "(define result 1)\n(display result)" (:source appended)))
          (Files/writeString (.resolve root "nested/code.lain") "external edit"
                             (make-array java.nio.file.OpenOption 0))
          (let [current (first (:files (files/poll! service ["nested/code.lain"])))]
            (is (= "external edit" (:source current)))
            (is (> (:sequence current) (:sequence appended)))
            (is (thrown? Exception (files/write-file! service {:path "nested/code.lain" :source "stale edit"
                                                               :baseVersion (:version appended)})))))
        (doseq [path ["../outside.lain" "/tmp/outside.lain"]]
          (is (thrown? Exception (files/read-file service path))))
        (Files/createSymbolicLink (.resolve root "link") (.getParent root)
                                  (make-array java.nio.file.attribute.FileAttribute 0))
        (is (thrown? Exception (files/write-file! service {:path "link/outside.lain" :source "blocked"})))))))

(deftest real-socket-file-service-is-independent-from-session-creation
  (with-root
    (fn [^Path root service]
      (let [path (str (.resolve root "files.sock"))
            bridge-calls (atom [])
            server (socket/start! {:path path :service service
                                   :bridge (fn [request] (swap! bridge-calls conj request) {:queued true})})]
        (try
          (with-open [channel (SocketChannel/open StandardProtocolFamily/UNIX)]
            (.connect channel (UnixDomainSocketAddress/of path))
            (let [writer (Channels/newWriter channel (.newEncoder StandardCharsets/UTF_8) -1)
                  reader (BufferedReader. (Channels/newReader channel (.newDecoder StandardCharsets/UTF_8) -1))
                  request! (fn [request]
                             (.write writer (str (json/write-json request) "\n"))
                             (.flush writer)
                             (json/read-json (.readLine reader)))]
              (is (true? (:ok (request! {:id "save" :op "files/write" :path "socket.lain" :source "draft ("}))))
              (is (= "draft (" (get-in (request! {:id "read" :op "files/read" :path "socket.lain"}) [:result :source])))
              (is (false? (:ok (request! {:op "open-session" :source "must not execute"}))))
              (is (empty? @bridge-calls))
              (is (true? (:ok (request! {:op "editor/extend-file" :path "socket.lain"}))))
              (is (= ["editor/extend-file"] (mapv :op @bridge-calls)))))
          (finally ((:close server))))
        (is (not (Files/exists (.resolve root "files.sock") (make-array java.nio.file.LinkOption 0))))))))

(deftest background-observer-detects-an-external-replacement-without-client-polling
  (with-root
    (fn [^Path root service]
      (let [before (files/write-file! service {:path "watched.lain" :source "initial"})
            target (.resolve root "watched.lain")
            replacement (.resolve root "replacement.tmp")]
        (Files/writeString replacement "external replacement" (make-array java.nio.file.OpenOption 0))
        (Files/move replacement target
                    (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
        (let [deadline (+ (System/nanoTime) 3000000000)
              observed (loop []
                         (let [value (get-in @(:state service) [:files "watched.lain"])]
                           (if (or (= "external replacement" (:source value)) (> (System/nanoTime) deadline))
                             value
                             (do (Thread/sleep 25) (recur)))))]
          (is (= "external replacement" (:source observed)))
          (is (not= (:version before) (:version observed)))
          (let [changes (files/command! service {:op "files/changes" :paths ["watched.lain"] :since (:sequence before)})]
            (is (= ["external replacement"] (mapv :source (:files changes))))
            (is (empty? (:files (files/command! service {:op "files/changes" :paths ["watched.lain"] :since (:sequence changes)}))))))))))

(deftest socket-startup-preserves-files-and-recovers-only-stale-owned-sockets
  (with-root
    (fn [^Path root service]
      (let [path (.resolve root "files.sock")]
        (Files/writeString path "preserve this file" (make-array java.nio.file.OpenOption 0))
        (is (thrown? Exception (socket/start! {:path (str path) :service service})))
        (is (= "preserve this file" (Files/readString path)))
        (Files/delete path)
        (with-open [stale (java.nio.channels.ServerSocketChannel/open StandardProtocolFamily/UNIX)]
          (.bind stale (UnixDomainSocketAddress/of path)))
        (let [server (socket/start! {:path (str path) :service service})]
          (try
            (is (thrown? Exception (socket/start! {:path (str path) :service service})))
            (finally ((:close server)))))
        (is (not (Files/exists path (make-array java.nio.file.LinkOption 0))))))))
