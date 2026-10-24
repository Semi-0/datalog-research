(ns graph.lain-editor.runtime.session
  "One standard runtime session per editor pane. Candidates commit after loading."
  (:refer-clojure :exclude [run!])
  (:require [graph.lain-editor.workspace.model :as workspace]
            [graph.lain-editor.runtime.display :as display]
            [graph.lain-editor.inspection.retained :as retained-inspection]
            [graph.lain-editor.inspection.semantic-capture :as semantic-capture]
            [graph.lain-editor.stack.primitives :as stack-primitives]
            [graph.lain-editor.runtime.slot-spawn :as slot-spawn]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.operators.network-history :as history]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.semantic-trace.retention :as retention]
            [propagators.semantic-trace.observation :as capture-observation]
            [propagators.experimental.visualization.extension :as visualization]))

(defn close-session! [session]
  (when-let [storage (get @session retained-inspection/storage-key)] (reset! storage {}))
  (semantic-capture/close! session)
  (runtime/stop-clocks! session)
  (doseq [trace (vals (:traces @session))] ((:stop trace))))

(defn load-checked! [session source]
  (when-not (string? source) (throw (ex-info "Source must be a string" {})))
  (let [loaded (loader/load-source! session source)]
    (when-let [error (get-in loaded [:load-result :error])]
      (throw (ex-info (str "Lain compilation failed: " error) {:compile-error error})))
    loaded))

(defn prepare [source extensions]
  (let [sources (atom {})
        session (runtime/new-session {semantic-capture/storage-key sources})]
    (runtime/ensure-session-state! session)
    (try
      (runtime/install-session-extension!
       session retention/extension {:outbox-id (runtime-ids/boundary-outbox-id)})
      (runtime/install-session-extension!
       session visualization/extension {:outbox-id (runtime-ids/boundary-outbox-id)})
      (runtime/install-session-extension! session display/extension {})
      (runtime/install-session-extension! session history/extension {})
      (runtime/install-session-extension! session capture-observation/extension {})
      (runtime/install-session-extension! session stack-primitives/extension {})
      (load-checked! session source)
      (doseq [extension extensions] (load-checked! session extension))
      (swap! session assoc
             semantic-capture/storage-key sources
             retained-inspection/storage-key (atom {}))
      {:session session :source source :extensions extensions
       :generation (str (random-uuid)) :sessionId (str (random-uuid))}
      (catch Throwable error
        (close-session! session)
        (throw error)))))

(defn require-pane [editors id]
  (or (get @editors id) (throw (ex-info "Run this pane first" {:pane id}))))

(defn require-generation [pane generation]
  (when-not (= generation (:generation pane))
    (throw (ex-info "Pane changed; refresh before retrying" {:stale true}))))

(defn promote! [editors id candidate]
  (let [previous (get @editors id)]
    (try
      (runtime/schedule-clock-subscriptions! (:session candidate))
      (runtime/refresh-trace-subscriptions! (:session candidate))
      (swap! editors assoc id candidate)
      (when previous (close-session! (:session previous)))
      (cond-> (select-keys candidate [:generation :sessionId])
        (display/mode (:program/net @(:session candidate)))
        (assoc :displayMode (display/mode (:program/net @(:session candidate)))))
      (catch Throwable error
        (close-session! (:session candidate))
        (throw error)))))

(defn run! [editors {:keys [pane source extensions] :or {extensions []}}]
  (when-not (string? pane) (throw (ex-info "Pane id required" {})))
  (when-not (and (vector? extensions) (every? string? extensions))
    (throw (ex-info "Extensions must be a vector of source strings" {})))
  (promote! editors pane (prepare source extensions)))

(defn extend! [editors {:keys [pane source generation]}]
  (let [previous (require-pane editors pane)
        _ (require-generation previous generation)
        original (:session previous)]
    (locking original
      ;; The immutable Net and binding IDs survive; only the candidate atom is new.
      (let [sources (atom @(semantic-capture/storage original))
            candidate (atom (assoc @original :traces {}
                                   semantic-capture/storage-key sources
                                   retained-inspection/storage-key (atom {})))]
        (try
          (swap! candidate assoc-in [:runtime/options semantic-capture/storage-key] sources)
          (load-checked! candidate source)
          (swap! candidate assoc semantic-capture/storage-key sources)
          (promote! editors pane
                    (assoc previous :session candidate
                           :generation (str (random-uuid))
                           :extensions (conj (:extensions previous) source)))
          (catch Throwable error
            (close-session! candidate)
            (throw error)))))))

(defn spawn-slots! [editors {:keys [pane generation] :as command}]
  (let [previous (require-pane editors pane)
        _ (require-generation previous generation)
        session (:session previous)]
    (locking session
      (let [state @session
            planned (slot-spawn/plan (:program/net state) (:program/env state))]
        (if (empty? (:slots planned))
          (assoc planned :generation generation)
          (merge planned (extend! editors (assoc command :source (:source planned)))))))))

(defn restore! [editors document]
  (workspace/validate document)
  (let [candidates (atom {})]
    (try
      (doseq [pane (:panes document)]
        (when (some? (:executedSource pane))
          (swap! candidates assoc (:id pane)
                 (prepare (:executedSource pane) (:extensions pane)))))
      (doseq [pane (vals @candidates)]
        (runtime/schedule-clock-subscriptions! (:session pane))
        (runtime/refresh-trace-subscriptions! (:session pane)))
      (let [previous @editors]
        (reset! editors @candidates)
        (doseq [pane (vals previous)] (close-session! (:session pane))))
      {:generations (into {} (map (fn [[id pane]] [id (:generation pane)])) @candidates)
       :displayModes (into {} (keep (fn [[id pane]]
                                     (when-let [mode (display/mode (:program/net @(:session pane)))]
                                       [id mode]))) @candidates)}
      (catch Throwable error
        (doseq [pane (vals @candidates)] (close-session! (:session pane)))
        (throw error)))))

(defn detached-entry [detached session-id]
  (or (get @detached session-id)
      (throw (ex-info "Detached session not found or already attached" {:sessionId session-id}))))

(defn detach! [editors detached {:keys [pane generation]}]
  (let [entry (require-pane editors pane)]
    (require-generation entry generation)
    (swap! detached assoc (:sessionId entry) (assoc entry :pane pane))
    (swap! editors dissoc pane)
    {:sessionId (:sessionId entry)}))

(defn list-detached [detached]
  {:sessions (mapv #(select-keys % [:sessionId :pane :source :extensions])
                   (sort-by :sessionId (vals @detached)))})

(defn reopen! [editors detached {:keys [pane sessionId]}]
  (when-not (and (string? pane) (not (empty? pane)))
    (throw (ex-info "Pane id required" {})))
  (when (contains? @editors pane)
    (throw (ex-info "Target pane is occupied" {:pane pane})))
  (let [entry (detached-entry detached sessionId)
        mode (display/mode (:program/net @(:session entry)))
        result (cond-> (select-keys entry [:sessionId :generation :source :extensions])
                 mode (assoc :displayMode mode))]
    (swap! editors assoc pane (dissoc entry :pane))
    (swap! detached dissoc sessionId)
    result))

(defn stop-detached! [detached {:keys [sessionId]}]
  (let [entry (detached-entry detached sessionId)]
    (close-session! (:session entry))
    (swap! detached dissoc sessionId)
    {:stopped sessionId}))

(defn remove! [editors id]
  (when-let [pane (get @editors id)] (close-session! (:session pane)))
  (swap! editors dissoc id)
  {:closed id})

(defn close! [editors]
  (doseq [pane (vals @editors)] (close-session! (:session pane)))
  (reset! editors {}))
