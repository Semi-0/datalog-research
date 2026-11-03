(ns graph.lain-editor.workspace.source-files
  "Explicit project-local source file writes; saving never evaluates a block."
  (:require [clojure.string :as str]
            [graph.lain-editor.workspace.project-files :as project-files]))

(defn save-block! [root {:keys [path source replace]}]
  (when-not (string? source) (throw (ex-info "Block source must be text" {})))
  (project-files/write-text!
   root {:path path :source source :replace replace :suffix ".lain"}))

(defn open-block! [root {:keys [path]}]
  (let [suffix (when (string? path) (some #(when (str/ends-with? (str/lower-case path) %) (subs path (- (count path) (count %)))) project-files/source-suffixes))]
    (when-not suffix (throw (ex-info "Choose a .lain, .clj or .txt file in the server workspace" {})))
    (project-files/read-text! root path suffix (* 1024 1024))))
