(ns graph.lain-editor.workspace.project-files
  "Safe project-relative text writes shared by editor persistence boundaries."
  (:require [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path Paths LinkOption OpenOption StandardOpenOption FileAlreadyExistsException]))

(defn directory-path
  "Resolve a workspace-relative path; never traverse symbolic links or escape root."
  [root filename]
  (when-not (string? filename) (throw (ex-info "Choose a workspace-relative path" {})))
  (let [base (.toRealPath (.toAbsolutePath (Paths/get (str root) (make-array String 0)))
                          (make-array LinkOption 0))
        relative (Paths/get filename (make-array String 0))
        target (.normalize (.resolve base relative))]
    (when (or (.isAbsolute relative) (not (.startsWith target base)))
      (throw (ex-info "Choose a path inside the server workspace" {})))
    (reduce (fn [^Path parent part]
              (let [child (.resolve parent ^Path part)]
                (when (Files/isSymbolicLink child)
                  (throw (ex-info "Workspace paths cannot traverse symbolic links" {})))
                child)) base (.relativize base target))
    target))

(defn project-path [root filename suffix]
  (when-not (and (string? filename) (not (str/blank? filename)) (str/ends-with? filename suffix))
    (throw (ex-info (str "Choose a workspace-relative " suffix " file path") {})))
  (directory-path root filename))

(defn read-text! [root filename suffix limit]
  (let [^Path target (project-path root filename suffix)]
    (when-not (Files/isRegularFile target (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (throw (ex-info "File not found in the server workspace" {})))
    (when (> (Files/size target) limit)
      (throw (ex-info "File exceeds the editor size limit" {})))
    {:source (Files/readString target StandardCharsets/UTF_8)
     :filename (str (.getFileName target)) :path filename}))

(def source-suffixes [".lain" ".clj" ".txt"])

(defn browse! [root {:keys [path kind] :or {path "" kind "source"}}]
  (when-not (contains? #{"source" "workspace"} kind)
    (throw (ex-info "Choose files or workspaces" {})))
  (let [^Path base (directory-path root "") ^Path target (directory-path root path)]
    (when-not (Files/isDirectory target (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (throw (ex-info "Choose a directory inside the server workspace" {})))
    (with-open [stream (Files/list target)]
      (let [entries
            (->> (iterator-seq (.iterator stream))
                 (remove #(or (Files/isSymbolicLink ^Path %) (str/starts-with? (str (.getFileName ^Path %)) ".")))
                 (keep (fn [^Path item]
                         (let [name (str (.getFileName item))
                               directory? (Files/isDirectory item (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                               source? (some #(str/ends-with? (str/lower-case name) %) source-suffixes)
                               workspace? (str/ends-with? (str/lower-case name) ".json")]
                           (when (or directory? (and (= kind "source") source?) (and (= kind "workspace") workspace?))
                             {:name name :path (str (.relativize base item))
                              :kind (if directory? "directory" "file")}))))
                 (sort-by (juxt #(if (= "directory" (:kind %)) 0 1) #(str/lower-case (:name %))))
                 vec)
            current (str (.relativize base target))]
        {:path current :parent (when-not (= base target) (str (.relativize base (.getParent target))))
         :entries (subvec entries 0 (min 1000 (count entries))) :truncated (> (count entries) 1000)}))))

(defn write-text!
  [root {:keys [path source replace suffix]}]
  (when-not (string? source)
    (throw (ex-info "File contents must be text" {})))
  (when-not (or (nil? replace) (boolean? replace))
    (throw (ex-info "Replace must be true or false" {})))
  (let [^Path target (project-path root path suffix)
        options (if replace
                  [StandardOpenOption/WRITE StandardOpenOption/CREATE StandardOpenOption/TRUNCATE_EXISTING]
                  [StandardOpenOption/WRITE StandardOpenOption/CREATE_NEW])]
    (Files/createDirectories (.getParent target) (make-array java.nio.file.attribute.FileAttribute 0))
    (try
      (Files/writeString target source StandardCharsets/UTF_8 (into-array OpenOption options))
      (catch FileAlreadyExistsException _
        (throw (ex-info "File already exists. Select Replace existing file to overwrite it." {}))))
    {:path path :bytes (alength (.getBytes source StandardCharsets/UTF_8))}))
