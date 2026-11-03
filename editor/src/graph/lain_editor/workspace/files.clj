(ns graph.lain-editor.workspace.files
  "Validated project-local persistence for editor workspace documents."
  (:require [clojure.string :as str]
            [graph.json :as json]
            [graph.lain-editor.workspace.project-files :as project-files]
            [graph.lain-editor.workspace.model :as workspace]))

(defn save-workspace!
  [root {:keys [path workspace replace]}]
  (let [document (workspace/validate workspace)
        source (str (json/write-json document) "\n")]
    (project-files/write-text!
     root {:path path :source source :replace replace :suffix ".json"})))

(defn open-workspace! [root {:keys [path]}]
  (when-not (and (string? path) (str/ends-with? (str/lower-case path) ".json"))
    (throw (ex-info "Choose a .json workspace" {})))
  (let [suffix (subs path (- (count path) 5))
        {:keys [source] :as file} (project-files/read-text! root path suffix (* 16 1024 1024))]
    (-> file (dissoc :source) (assoc :workspace (workspace/upgrade (json/read-json source))))))
