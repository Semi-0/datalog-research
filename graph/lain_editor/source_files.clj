(ns graph.lain-editor.source-files
  "Explicit project-local source file writes; saving never evaluates a block."
  (:require [clojure.string :as str])
  (:import [java.nio.file Files Path Paths LinkOption OpenOption StandardOpenOption FileAlreadyExistsException]
           [java.nio.charset StandardCharsets]))

(defn- project-path [root filename]
  (when-not (and (string? filename) (not (str/blank? filename)) (str/ends-with? filename ".lain"))
    (throw (ex-info "Choose a project-relative .lain file path" {})))
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

(defn save-block! [root {:keys [path source replace]}]
  (when-not (string? source) (throw (ex-info "Block source must be text" {})))
  (when-not (or (nil? replace) (boolean? replace))
    (throw (ex-info "Replace must be true or false" {})))
  (let [^Path target (project-path root path)
        options (if replace
                  [StandardOpenOption/WRITE StandardOpenOption/CREATE StandardOpenOption/TRUNCATE_EXISTING]
                  [StandardOpenOption/WRITE StandardOpenOption/CREATE_NEW])]
    (Files/createDirectories (.getParent target) (make-array java.nio.file.attribute.FileAttribute 0))
    (try
      (Files/writeString target source StandardCharsets/UTF_8 (into-array OpenOption options))
      (catch FileAlreadyExistsException _
        (throw (ex-info "File already exists. Select Replace existing file to overwrite it." {}))))
    {:path path :bytes (alength (.getBytes ^String source StandardCharsets/UTF_8))}))
