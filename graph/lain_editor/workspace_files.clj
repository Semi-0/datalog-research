(ns graph.lain-editor.workspace-files
  "Validated project-local persistence for editor workspace documents."
  (:require [graph.json :as json]
            [graph.lain-editor.project-files :as project-files]
            [graph.lain-editor.workspace :as workspace]))

(defn save-workspace!
  [root {:keys [path workspace replace]}]
  (let [document (workspace/validate workspace)
        source (str (json/write-json document) "\n")]
    (project-files/write-text!
     root {:path path :source source :replace replace :suffix ".json"})))
