(ns graph.lain-editor.runtime.session
  "Editor-owned sessions and read-only views of independently owned sessions.
  Registry transitions are serialized by the server's editor command lock.
  Owned sessions transfer; external views retain discovery and their generation."
  (:refer-clojure :exclude [run!])
  (:require [graph.lain-editor.workspace.model :as workspace]
            [graph.lain-editor.runtime.display :as display]
            [graph.lain-editor.inspection.retained :as retained-inspection]
            [graph.lain-editor.inspection.semantic-capture :as semantic-capture]
            [graph.lain-editor.stack.primitives :as stack-primitives]
            [graph.lain-editor.runtime.slot-spawn :as slot-spawn]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.session :as compiler-session]
            [propagators.compiler-2.session.host :as compiler-host]
            [propagators.compiler-2.session.files :as compiler-files]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.operators.network-history :as history]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.semantic-trace.retention :as retention]
            [propagators.semantic-trace.observation :as capture-observation]
            [propagators.experimental.visualization.extension :as visualization]))

(defn close-session! [session]
  (when-let [storage (get @session retained-inspection/storage-key)] (reset! storage {}))
  (semantic-capture/close! session)
  (if-let [boundary (compiler-host/boundary-for session)]
    (compiler-host/close! boundary)
    (do
      (runtime/stop-clocks! session)
      (doseq [trace (vals (:traces @session))] ((:stop trace))))))

(defn- require-owned! [entry]
  (when (= :external (:ownership entry))
    (throw (ex-info "This pane observes an externally owned session; use its owner to change or stop it"
                    {:external-session true}))))

(defn- close-entry! [entry]
  (when-not (= :external (:ownership entry))
    (close-session! (:session entry))))

(defn observe!
  "Register a read-only view of an existing session. No clock, pump, or cleanup is acquired."
  [editors {:keys [pane session sessionId source]}]
  (when-not (and (string? pane) (string? sessionId)
                 (instance? clojure.lang.IAtom session) (string? source))
    (throw (ex-info "Observer requires pane, session atom, sessionId, and source" {})))
  (when (contains? @editors pane)
    (throw (ex-info "Target pane is occupied" {:pane pane})))
  (let [entry {:session session :sessionId sessionId :source source :extensions []
               :generation (str (random-uuid)) :ownership :external}]
    (swap! editors assoc pane entry)
    (select-keys entry [:generation :sessionId])))

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

(defn prepare-compiler
  "Opt-in immutable compiler backend, using the existing editor capabilities."
  [source extensions]
  (when-not (string? source) (throw (ex-info "Source must be a string" {})))
  (let [sources (atom {})
        initial (compiler-session/create-session
                 {:id (random-uuid) :run-id (random-uuid)
                  :runtime-options {semantic-capture/storage-key sources}
                  :native-extensions [retention/extension visualization/extension
                                      display/extension history/extension
                                      capture-observation/extension stack-primitives/extension]})
        declarations (compiler-files/prepare-extension!
                       {:id ::editor-source
                        :files (into {"main.lain" source}
                                     (map-indexed (fn [index text]
                                                    [(str "editor-extension-" index ".lain") text])
                                                  extensions))})
        compiled (compiler-session/extend-session initial declarations)
        boundary (compiler-host/open! compiled)
        session (compiler-host/runtime-session boundary)]
    (try
      (compiler-host/step! boundary)
      (swap! session assoc semantic-capture/storage-key sources
             retained-inspection/storage-key (atom {}))
      {:session session :source source :extensions extensions
       :compilerSession true
       :generation (str (random-uuid))
       :sessionId (str (:id (compiler-session/session-identity compiled)))}
      (catch Throwable error
        (compiler-host/close! boundary)
        (throw error)))))

(defn register-compiler-session!
  "Expose an independently owned headless boundary through the detached registry.
  No compilation, subscription installation, or dependency launch occurs here."
  [detached boundary source]
  (when-not (string? source) (throw (ex-info "Source must be a string" {})))
  (let [identity (compiler-session/session-identity (compiler-host/current-session boundary))
        id (str (:id identity))
        entry {:session (compiler-host/runtime-session boundary)
               :source source :extensions [] :compilerSession true
               :generation (str (random-uuid)) :sessionId id}]
    (when (contains? @detached id)
      (throw (ex-info "Compiler session is already registered" {:sessionId id})))
    (swap! detached assoc id entry)
    (select-keys entry [:sessionId :generation])))

(defn require-pane [editors id]
  (or (get @editors id) (throw (ex-info "Run this pane first" {:pane id}))))

(defn require-generation [pane generation]
  (when-not (= generation (:generation pane))
    (throw (ex-info "Pane changed; refresh before retrying" {:stale true}))))

(defn promote! [editors id candidate]
  (let [previous (get @editors id)]
    (when previous (require-owned! previous))
    (require-owned! candidate)
    (try
      (runtime/schedule-clock-subscriptions! (:session candidate))
      (runtime/refresh-trace-subscriptions! (:session candidate))
      (swap! editors assoc id candidate)
      (when previous (close-entry! previous))
      (cond-> (select-keys candidate [:generation :sessionId])
        (display/mode (:program/net @(:session candidate)))
        (assoc :displayMode (display/mode (:program/net @(:session candidate)))))
      (catch Throwable error
        (close-session! (:session candidate))
        (throw error)))))

(defn run! [editors {:keys [pane source extensions compilerSession] :or {extensions []} :as command}]
  (when-not (string? pane) (throw (ex-info "Pane id required" {})))
  (when-not (and (vector? extensions) (every? string? extensions))
    (throw (ex-info "Extensions must be a vector of source strings" {})))
  (when-let [previous (get @editors pane)] (require-owned! previous))
  (let [previous (get @editors pane)
        boundary (when-let [session (:session previous)]
                   (compiler-host/boundary-for session))]
    (if (and boundary (= source (:source previous)) (or (not (contains? command :extensions)) (= extensions (:extensions previous))))
      (do
        (compiler-host/rerun! boundary (random-uuid))
        (let [updated (assoc previous :generation (str (random-uuid)))]
          (swap! editors assoc pane updated)
          (select-keys updated [:generation :sessionId])))
      (promote! editors pane ((if (or compilerSession boundary) prepare-compiler prepare)
                             source extensions)))))

(defn- extend-legacy! [editors {:keys [pane source generation]}]
  (let [previous (require-pane editors pane)
        _ (require-generation previous generation)
        _ (require-owned! previous)
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

(defn extend! [editors {:keys [pane source generation] :as command}]
  (let [previous (require-pane editors pane)]
    (require-owned! previous)
    (require-generation previous generation)
    (if-let [boundary (compiler-host/boundary-for (:session previous))]
      (do
        (compiler-host/extend-source! boundary source)
        (let [updated (-> previous
                          (assoc :generation (str (random-uuid)))
                          (update :extensions conj source))]
          (swap! editors assoc pane updated)
          (select-keys updated [:generation :sessionId])))
      (extend-legacy! editors command))))

(defn spawn-slots! [editors {:keys [pane generation] :as command}]
  (let [previous (require-pane editors pane)
        _ (require-generation previous generation)
        _ (require-owned! previous)
        session (:session previous)]
    (locking session
      (let [state @session
            planned (slot-spawn/plan (:program/net state) (:program/env state))]
        (if (empty? (:slots planned))
          (assoc planned :generation generation)
          (merge planned (extend! editors (assoc command :source (:source planned)))))))))

(defn restore! [editors document]
  (workspace/validate document)
  (let [external (into {} (filter (fn [[_ entry]] (= :external (:ownership entry)))) @editors)
        visible-external (select-keys external (map :id (:panes document)))
        candidates (atom {})]
    (try
      (doseq [pane (:panes document)]
        (if-let [entry (get external (:id pane))]
          (when-not (and (= (:executedSource pane) (:source entry))
                         (= (:extensions pane) (:extensions entry)))
            (require-owned! entry))
          (when (some? (:executedSource pane))
            (swap! candidates assoc (:id pane)
                   (prepare (:executedSource pane) (:extensions pane))))))
      (doseq [pane (vals @candidates)]
        (runtime/schedule-clock-subscriptions! (:session pane))
        (runtime/refresh-trace-subscriptions! (:session pane)))
      (let [previous @editors]
        (reset! editors (merge external @candidates))
        (doseq [pane (vals previous)] (close-entry! pane)))
      (let [restored (merge visible-external @candidates)]
        {:generations (into {} (map (fn [[id pane]] [id (:generation pane)])) restored)
         :displayModes (into {} (keep (fn [[id pane]]
                                       (when-let [mode (display/mode (:program/net @(:session pane)))]
                                         [id mode]))) restored)})
      (catch Throwable error
        (doseq [pane (vals @candidates)] (close-entry! pane))
        (throw error)))))

(defn detached-entry [detached session-id]
  (or (get @detached session-id)
      (throw (ex-info "Detached session not found or already attached" {:sessionId session-id}))))

(defn detach!
  "Transfer an executed pane into detached ownership without touching its runtime."
  [editors detached {:keys [pane generation]}]
  (let [entry (require-pane editors pane)]
    (require-generation entry generation)
    (swap! detached assoc (:sessionId entry) (assoc entry :pane pane))
    (swap! editors dissoc pane)
    {:sessionId (:sessionId entry)}))

(defn list-detached [detached]
  {:sessions (mapv #(select-keys % [:sessionId :pane :source :extensions])
                   (sort-by :sessionId (vals @detached)))})

(defn reopen!
  "Transfer an owned session, or attach another view of an external owner.
  External discovery entries remain available without acquiring lifetime authority."
  [editors detached {:keys [pane sessionId]}]
  (when-not (and (string? pane) (not (empty? pane)))
    (throw (ex-info "Pane id required" {})))
  (when (contains? @editors pane)
    (throw (ex-info "Target pane is occupied" {:pane pane})))
  (let [entry (detached-entry detached sessionId)
        mode (display/mode (:program/net @(:session entry)))
        result (cond-> (select-keys entry [:sessionId :generation :source :extensions])
                 mode (assoc :displayMode mode))]
    (swap! editors assoc pane (dissoc entry :pane))
    (when-not (= :external (:ownership entry))
      (swap! detached dissoc sessionId))
    result))

(defn stop-detached! [detached {:keys [sessionId]}]
  (let [entry (detached-entry detached sessionId)]
    (require-owned! entry)
    (close-entry! entry)
    (swap! detached dissoc sessionId)
    {:stopped sessionId}))

(defn remove! [editors id]
  (when-let [pane (get @editors id)] (close-entry! pane))
  (swap! editors dissoc id)
  {:closed id})

(defn close! [editors]
  (doseq [pane (vals @editors)] (close-entry! pane))
  (reset! editors {}))
