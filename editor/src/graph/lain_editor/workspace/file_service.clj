(ns graph.lain-editor.workspace.file-service
  "Rooted file operations, version checks and change observation; no session dependency."
  (:require [clojure.string :as str]
            [graph.lain-editor.workspace.project-files :as paths])
  (:import [java.nio.file Files Path LinkOption StandardCopyOption StandardWatchEventKinds WatchEvent$Kind WatchKey]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util.concurrent TimeUnit]))

(def max-bytes (* 1024 1024))

(defn- digest [^String source]
  (apply str (map #(format "%02x" (bit-and 255 %))
                  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes source StandardCharsets/UTF_8)))))

(defn read-file [service path]
  (when-not (and (string? path) (not (str/blank? path)))
    (throw (ex-info "A workspace-relative file path is required" {})))
  (let [^Path target (paths/directory-path (:root service) path)]
    (if-not (Files/exists target (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      {:path path :exists false :version nil}
      (do
        (when-not (Files/isRegularFile target (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
          (throw (ex-info "Choose a regular workspace file" {:path path})))
        (when (> (Files/size target) max-bytes)
          (throw (ex-info "Source file exceeds the 1 MiB limit" {:path path})))
        (let [source (Files/readString target StandardCharsets/UTF_8)]
          {:path path :exists true :source source :version (digest source)})))))

(defn- observe! [service path]
  (let [current (try (read-file service path)
                     (catch Exception error {:path path :error (ex-message error)}))]
    (locking (:state service)
      (let [before (get-in @(:state service) [:files path])]
        (when (not= current (dissoc before :sequence))
          (swap! (:state service)
                 (fn [state]
                   (let [sequence (inc (:sequence state))]
                     (-> state (assoc :sequence sequence)
                         (assoc-in [:files path] (assoc current :sequence sequence)))))))
        (get-in @(:state service) [:files path])))))

(defn watch! [service path]
  (read-file service path)
  (locking (:tracked service)
    (when (and (not (contains? @(:tracked service) path)) (>= (count @(:tracked service)) 256))
      (throw (ex-info "File watcher capacity reached" {})))
    (swap! (:tracked service) conj path))
  (let [^Path parent (.getParent (paths/directory-path (:root service) path))]
    (when (Files/isDirectory parent (make-array LinkOption 0))
      (locking (:directories service)
        (when-not (contains? @(:directories service) parent)
          (let [key (.register parent (:watcher service)
                               (into-array WatchEvent$Kind [StandardWatchEventKinds/ENTRY_CREATE
                                                            StandardWatchEventKinds/ENTRY_MODIFY
                                                            StandardWatchEventKinds/ENTRY_DELETE]))]
            (swap! (:directories service) assoc parent key))))))
  (observe! service path))

(defn poll! [service paths]
  (when-not (and (vector? paths) (<= (count paths) 64) (every? string? paths))
    (throw (ex-info "Observe at most 64 workspace file paths" {})))
  {:files (mapv (fn [path]
                  (try (watch! service path)
                       (catch Exception error {:path path :error (ex-message error)})))
                (distinct paths))})

(defn changes
  "Read watcher notifications without rescanning file contents. Watch paths first."
  [service requested since]
  (when-not (and (vector? requested) (<= (count requested) 64)
                 (every? string? requested) (integer? since) (<= 0 since))
    (throw (ex-info "Changes requires file paths and a nonnegative sequence" {})))
  (doseq [path requested] (paths/directory-path (:root service) path))
  (let [state @(:state service)]
    {:sequence (:sequence state)
     :files (into [] (keep (fn [path]
                             (let [snapshot (get-in state [:files path])]
                               (when (and snapshot (> (:sequence snapshot) since)) snapshot))))
                  (distinct requested))}))

(defn write-file! [service {:keys [path source baseVersion append]}]
  (when-not (string? source) (throw (ex-info "File source must be text" {})))
  (when-not (or (nil? append) (boolean? append))
    (throw (ex-info "Append must be boolean" {})))
  (locking (:state service)
    (let [before (read-file service path)]
      (when-not (= baseVersion (:version before))
        (throw (ex-info "File changed; reload or resolve the conflict before saving"
                        {:conflict true :path path :version (:version before)})))
      (let [text (if append (str (:source before) source) source)
            bytes (.getBytes ^String text StandardCharsets/UTF_8)
            ^Path target (paths/directory-path (:root service) path)]
        (when (> (alength bytes) max-bytes)
          (throw (ex-info "Source file exceeds the 1 MiB limit" {})))
        (Files/createDirectories (.getParent target) (make-array java.nio.file.attribute.FileAttribute 0))
        (let [temporary (Files/createTempFile (.getParent target) ".lain-save-" ".tmp"
                                              (make-array java.nio.file.attribute.FileAttribute 0))]
          (try
            (Files/write temporary bytes (make-array java.nio.file.OpenOption 0))
            ;; Revalidate after writing the temporary file, before publishing it.
            (when-not (= baseVersion (:version (read-file service path)))
              (throw (ex-info "File changed during save" {:conflict true :path path})))
            (Files/move temporary target
                        (into-array java.nio.file.CopyOption
                                    [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
            (finally (Files/deleteIfExists temporary))))
        (watch! service path)))))

(defn command! [service {:keys [op path paths] :as command}]
  (case op
    "files/list" (paths/browse! (:root service) {:path (or path "") :kind (get command :kind "source")})
    "files/read" (read-file service path)
    "files/watch" (watch! service path)
    "files/poll" (poll! service paths)
    "files/changes" (changes service paths (get command :since 0))
    "files/write" (write-file! service command)
    "files/append" (write-file! service (assoc command :append true))
    (throw (ex-info "Unknown file-service operation" {:op op}))))

(defn start! [root]
  (let [^Path base (paths/directory-path root "")
        watcher (.newWatchService (.getFileSystem base))
        closed? (atom false)
        service {:root root :state (atom {:sequence 0 :files {}}) :tracked (atom #{})
                 :watcher watcher :directories (atom {})}
        thread (Thread.
                ^Runnable
                (fn []
                  (while (not @closed?)
                    (try
                      (when-let [^WatchKey key (.poll watcher 500 TimeUnit/MILLISECONDS)]
                        (let [^Path directory (.watchable key)
                              events (.pollEvents key)
                              overflow? (some #(= StandardWatchEventKinds/OVERFLOW (.kind ^java.nio.file.WatchEvent %)) events)
                              changed (into #{} (keep (fn [event]
                                                        (when-not (= StandardWatchEventKinds/OVERFLOW (.kind ^java.nio.file.WatchEvent event))
                                                          (.resolve directory ^Path (.context ^java.nio.file.WatchEvent event))))) events)]
                          (doseq [path @(:tracked service)]
                            (when (or overflow?
                                      (try (contains? changed (paths/directory-path root path))
                                           (catch Exception _ true)))
                              (observe! service path)))
                          (when-not (.reset key)
                            (swap! (:directories service)
                                   #(into {} (remove (fn [[_ value]] (= key value))) %)))))
                      (catch Exception _
                        (when-not @closed? (reset! closed? true))))))
                "lain-file-observer")]
    (.setDaemon thread true)
    (.start thread)
    (assoc service :close #(do (reset! closed? true) (.close watcher) (.join thread 1000)))))
