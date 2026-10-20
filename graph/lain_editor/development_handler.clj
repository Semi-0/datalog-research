(ns graph.lain-editor.development-handler
  "Focused HTTP operation dispatch for editor development sessions."
  (:require [graph.lain-editor.development-protocol :as protocol]
            [graph.lain-editor.development-runtime :as development]))

(defn command! [registry {:keys [op pane] :as command}]
  (case op
    "development-start" (development/start! registry command)
    "development-commit" (development/commit! registry command)
    "development-checkpoint" (development/checkpoint! registry command)
    "development-export" (development/export registry command)
    "development-close" (development/close! registry command)
    (throw (ex-info "Unknown development operation" {:op op}))))
