(ns graph.lain-editor.source-files
  "Explicit project-local source file writes; saving never evaluates a block."
  (:require [graph.lain-editor.project-files :as project-files]))

(defn save-block! [root {:keys [path source replace]}]
  (when-not (string? source) (throw (ex-info "Block source must be text" {})))
  (project-files/write-text!
   root {:path path :source source :replace replace :suffix ".lain"}))
