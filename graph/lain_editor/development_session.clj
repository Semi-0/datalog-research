(ns graph.lain-editor.development-session
  "Editor-development session assembly. Stack and frozen-capture capabilities
  stay opt-in and do not change the ordinary compiler application assembly."
  (:require [graph.compiler-2-assembly :as assembly]
            [graph.lain-editor.stack-primitives :as stack-primitives]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.semantic-trace.observation :as capture-observation]
            [propagators.semantic-trace.retention :as retention]))

(defn runtime-options []
  (-> (assembly/runtime-options)
      (update :operator-groups conj :stack)
      (assoc-in [:operator-providers :stack]
                (fn [_] (stack-primitives/bindings)))))

(defn new-session []
  (let [sources (atom {})
        session (runtime/new-session
                 (assoc (runtime-options) retention/storage-key sources))]
    (runtime/install-session-extension!
     session retention/extension {:outbox-id (runtime-ids/boundary-outbox-id)})
    (runtime/install-session-extension! session capture-observation/extension {})
    (swap! session assoc retention/storage-key sources)
    session))
