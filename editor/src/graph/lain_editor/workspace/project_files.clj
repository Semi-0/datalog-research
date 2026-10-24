(ns graph.lain-editor.workspace.project-files
  "Safe project-relative text writes shared by editor persistence boundaries."
  (:require [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path Paths LinkOption OpenOption StandardOpenOption FileAlreadyExistsException]))

(defn project-path
  [root filename suffix]
  (when-not (and (string? filename) (not (str/blank? filename)) (str/ends-with? filename suffix))
    (throw (ex-info (str "Choose a project-relative " suffix " file path") {})))
  (let [root (.toRealPath (.toAbsolutePath (Paths/get (str root) (make-array String 0)))
                          (make-array LinkOption 0))
        relative (Paths/get filename (make-array String 0))
        target (.normalize (.resolve root relative))]
    (when (or (.isAbsolute relative) (not (.startsWith target root)) (= root target))
      (throw (ex-info "Save files inside the project directory" {})))
    (reduce (fn [^Path parent part]
              (let [child (.resolve parent ^Path part)]
                (when (Files/isSymbolicLink child)
                  (throw (ex-info "Save path cannot traverse symbolic links" {})))
                child)) root (.relativize root target))
    target))

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
