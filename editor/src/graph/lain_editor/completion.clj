(ns graph.lain-editor.completion
  "Read-only names visible from the current editor session's top-level environment."
  (:require [graph.lain-editor.runtime.session :as editor]
            [propagators.compiler-2.model.env :as env]))

(defn candidates
  "Observe one snapshot without reading binding values, evaluating code, or publishing state."
  [editors {:keys [pane generation]}]
  (when-not (and (string? pane) (string? generation))
    (throw (ex-info "Completion requires pane and generation strings" {})))
  (locking editors
    (let [entry (editor/require-pane editors pane)
          _ (editor/require-generation entry generation)
          state @(:session entry)
          network (:program/net state)
          names (->> (vals (env/binding-names network))
                     distinct
                     (filter #(env/lexical-binding-id network % (:program/env state)))
                     (map str)
                     sort
                     vec)]
      {:generation generation
       :sessionId (:sessionId entry)
       :candidates (mapv (fn [name] {:label name :origin "environment"}) (take 5000 names))
       :truncated (> (count names) 5000)})))
