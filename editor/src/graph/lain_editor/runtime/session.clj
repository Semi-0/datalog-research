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
  (if-let [boundary (compiler-host/boundary-for session)]
    (compiler-host/close! boundary)
    (do
      (runtime/stop-clocks! session)
      (doseq [trace (vals (:traces @session))] ((:stop trace)))))
  (when-let [storage (get @session retained-inspection/storage-key)] (reset! storage {}))
  (semantic-capture/close! session))

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

(def ^:dynamic *owners* nil)

(defn service-state
  "Reuse explicit service registries across convenience command calls and tests."
  [editors]
  (or (::service (meta editors))
      (locking editors
        (or (::service (meta editors))
            (let [state {:owners (atom {}) :layouts (atom {}) :developments (atom {})}]
              (alter-meta! editors assoc ::service state)
              state)))))

(defn owner-catalog [editors] (or *owners* (:owners (service-state editors))))

(defn require-pane [editors id]
  (let [entry (or (get @editors id) (throw (ex-info "Run this pane first" {:pane id})))]
    (if (:managed entry)
      (or (get @(owner-catalog editors) (:sessionId entry))
          (throw (ex-info "Session is no longer available; Run explicitly" {:sessionId (:sessionId entry)})))
      entry)))

(defn require-generation [entry generation]
  (when-not (= generation (:generation entry))
    (throw (ex-info "Pane changed; refresh before retrying" {:stale true}))))

(defn require-owner [editors {:keys [pane sessionId]}]
  (if sessionId
    (or (get @(owner-catalog editors) sessionId)
        (some (fn [entry] (when (= sessionId (:sessionId entry)) entry)) (vals @editors))
        (throw (ex-info "Session not found; Run explicitly" {:sessionId sessionId})))
    (require-pane editors pane)))

(defn- public-entry [entry]
  (let [boundary (compiler-host/boundary-for (:session entry))
        identity (when boundary (compiler-session/session-identity (compiler-host/current-session boundary)))
        mode (display/mode (:program/net @(:session entry)))]
    (cond-> (assoc (select-keys entry [:sessionId :generation :source :extensions
                                     :appliedExtensions :compilerSession :ownership])
                   :runtime (if boundary "session" "legacy")
                   :runId (when identity (str (:run-id identity)))
                   :lifecycle (if boundary (name (compiler-host/lifecycle boundary)) "active"))
      mode (assoc :displayMode mode))))

(defn session-status [editors command] (public-entry (require-owner editors command)))

(defn- publish-owner! [editors entry]
  (swap! (owner-catalog editors) assoc (:sessionId entry) entry)
  (swap! editors (fn [views]
                   (into {} (map (fn [[id view]]
                                   [id (if (= (:sessionId entry) (:sessionId view)) entry view)])) views)))
  (public-entry entry))

(defn- source-extension [_current id text]
  ;; Snapshot only this extension and its explicit imports. Keep duplicate
  ;; explicit imports in its own declaration so removing another extension
  ;; cannot remove capabilities this extension independently committed.
  (compiler-files/prepare-extension!
   {:id id :files {(str "editor/" id ".lain") text}}))

(defn- compile-owner [source applied identity & [committed]]
  (when-not (string? source) (throw (ex-info "Source must be a string" {})))
  (let [sources (atom {})
        initial (compiler-session/create-session
                 (merge identity
                        {:runtime-options {semantic-capture/storage-key sources}
                         ;; Interactive editor programs include the existing search
                         ;; gallery. Keep a finite wall budget and the API step cap.
                         :propagation-options {:max-steps 100000 :timeout-ms 180000}
                         :native-extensions [retention/extension visualization/extension
                                             display/extension history/extension
                                             capture-observation/extension stack-primitives/extension]}))
        base (compiler-session/extend-session initial
                                             (or (get committed "base") (source-extension initial "base" source)))
        compiled (reduce (fn [current {:keys [id source]}]
                           (compiler-session/extend-session current
                                                             (or (get committed id) (source-extension current id source))))
                         base applied)]
    (compiler-session/with-compiler-state
     compiled (assoc (compiler-session/compiler-state compiled)
                     semantic-capture/storage-key sources retained-inspection/storage-key (atom {})))))

(defn- committed-declarations [compiled]
  (into {} (map (fn [[id parts]] [id (compiler-session/session-extension (assoc parts :id id))]))
        (:extensions (compiler-session/session-declarations compiled))))

(defn- applied-extensions [extensions]
  (when-not (and (vector? extensions) (every? string? extensions))
    (throw (ex-info "Extensions must be a vector of source strings" {})))
  (mapv (fn [source] {:id (str (random-uuid)) :source source}) extensions))

(defn prepare-compiler
  "Create a managed immutable session through the supported host boundary."
  [source extensions]
  (let [applied (applied-extensions extensions)
        compiled (compile-owner source applied {:id (random-uuid) :run-id (random-uuid)})
        boundary (compiler-host/open! compiled)]
    (try
      (compiler-host/step! boundary)
      {:session (compiler-host/runtime-session boundary) :source source :extensions extensions
       :appliedExtensions applied :committedDeclarations (committed-declarations compiled)
       :managed true :compilerSession true
       :generation (str (random-uuid))
       :sessionId (str (:id (compiler-session/session-identity compiled)))}
      (catch Throwable error (compiler-host/close! boundary) (throw error)))))

(defn open-session! [editors {:keys [source extensions] :or {extensions []}}]
  (publish-owner! editors (prepare-compiler source extensions)))

(defn register-compiler-session!
  "Expose an independently owned headless boundary as protected discovery."
  [owners boundary source]
  (when-not (string? source) (throw (ex-info "Source must be a string" {})))
  (let [identity (compiler-session/session-identity (compiler-host/current-session boundary))
        id (str (:id identity))
        entry {:session (compiler-host/runtime-session boundary) :source source :extensions []
               :compilerSession true :ownership :external
               :generation (str (random-uuid)) :sessionId id}]
    (when (contains? @owners id) (throw (ex-info "Compiler session is already registered" {:sessionId id})))
    (swap! owners assoc id entry)
    (public-entry entry)))

(defn promote! [editors id candidate]
  (let [previous (get @editors id)]
    (when previous (require-owned! previous))
    (try
      (runtime/schedule-clock-subscriptions! (:session candidate))
      (runtime/refresh-trace-subscriptions! (:session candidate))
      (when previous
        (close-entry! previous)
        (when (:managed previous)
          (swap! (owner-catalog editors) dissoc (:sessionId previous))
          (swap! editors (fn [views]
                           (into {} (remove (fn [[_ entry]] (= (:sessionId previous) (:sessionId entry)))) views)))))
      (swap! editors assoc id candidate)
      (public-entry candidate)
      (catch Throwable error (close-session! (:session candidate)) (throw error)))))

(defn run! [editors {:keys [pane source extensions compilerSession generation] :as command}]
  (when-not (string? pane) (throw (ex-info "Pane id required" {})))
  (let [previous (when (get @editors pane) (require-pane editors pane))
        _ (when previous (require-owned! previous))
        _ (when (and previous generation) (require-generation previous generation))
        legacy? (false? compilerSession)
        extensions (if (contains? command :extensions) extensions (or (:extensions previous) []))
        _ (applied-extensions extensions)]
    (if legacy?
      (promote! editors pane (assoc (prepare source extensions) :compilerSession false))
      (if (:managed previous)
        (let [boundary (compiler-host/boundary-for (:session previous))
              applied (if (= extensions (:extensions previous))
                        (:appliedExtensions previous) (applied-extensions extensions))
              identity (compiler-session/session-identity (compiler-host/current-session boundary))
              candidate (compile-owner source applied (assoc identity :run-id (random-uuid)))]
          (compiler-host/replace! boundary candidate)
          (publish-owner! editors (assoc previous :source source :extensions extensions
                                        :appliedExtensions applied :committedDeclarations (committed-declarations candidate)
                                        :generation (str (random-uuid)))))
        (let [entry (prepare-compiler source extensions)]
          (when previous (close-entry! previous))
          (publish-owner! editors entry)
          (swap! editors assoc pane entry)
          (public-entry entry))))))

(defn- extend-legacy! [editors {:keys [pane source generation]}]
  (let [previous (require-pane editors pane)
        _ (require-generation previous generation)
        _ (require-owned! previous)
        original (:session previous)]
    (locking original
      (let [sources (atom @(semantic-capture/storage original))
            candidate (atom (assoc @original :traces {}
                                   semantic-capture/storage-key sources
                                   retained-inspection/storage-key (atom {})))]
        (try
          (swap! candidate assoc-in [:runtime/options semantic-capture/storage-key] sources)
          (load-checked! candidate source)
          (swap! candidate assoc semantic-capture/storage-key sources)
          (promote! editors pane (assoc previous :session candidate :generation (str (random-uuid))
                                       :extensions (conj (:extensions previous) source)))
          (catch Throwable error (close-session! candidate) (throw error)))))))

(defn extend! [editors {:keys [source generation] :as command}]
  (let [previous (require-owner editors command)]
    (require-owned! previous)
    (require-generation previous generation)
    (if (:managed previous)
      (let [boundary (compiler-host/boundary-for (:session previous))
            applied {:id (str (random-uuid)) :source source}
            extension (source-extension (compiler-host/current-session boundary) (:id applied) source)]
        (compiler-host/extend! boundary extension)
        (publish-owner! editors (-> previous
                                   (assoc :generation (str (random-uuid)))
                                   (update :extensions conj source)
                                   (update :appliedExtensions conj applied)
                                   (update :committedDeclarations assoc (:id applied) extension))))
      (extend-legacy! editors command))))

(defn remove-extension! [editors {:keys [generation extensionId] :as command}]
  (let [previous (require-owner editors command)]
    (require-owned! previous)
    (require-generation previous generation)
    (when-not (and (:managed previous)
                   (some #(= extensionId (:id %)) (:appliedExtensions previous)))
      (throw (ex-info "Select an applied extension; base and bootstrap cannot be removed" {})))
    (let [applied (filterv #(not= extensionId (:id %)) (:appliedExtensions previous))
          boundary (compiler-host/boundary-for (:session previous))
          identity (compiler-session/session-identity (compiler-host/current-session boundary))
          candidate (compile-owner (:source previous) applied (assoc identity :run-id (random-uuid))
                                   (:committedDeclarations previous))]
      (compiler-host/replace! boundary candidate)
      (publish-owner! editors (assoc previous :appliedExtensions applied
                                    :extensions (mapv :source applied) :committedDeclarations (committed-declarations candidate)
                                    :generation (str (random-uuid)))))))

(defn spawn-slots! [editors {:keys [generation] :as command}]
  (let [previous (require-owner editors command)
        _ (require-generation previous generation)
        _ (require-owned! previous)
        session (:session previous)]
    ;; The host takes its own round lock. Do not acquire its view lock first.
    (let [state @session
          planned (slot-spawn/plan (:program/net state) (:program/env state))]
      (if (empty? (:slots planned))
        (assoc planned :generation generation)
        (merge (extend! editors (assoc command :source (:source planned))) planned)))))

(defn restore!
  "Restore views using private reconnect references. Portable workspaces never execute."
  ([editors document] (restore! editors document {}))
  ([editors document reconnect]
   (workspace/validate document)
   (when-not (map? reconnect) (throw (ex-info "Reconnect references must be a map" {})))
   (let [owners @(owner-catalog editors)
         external (into {} (filter (fn [[_ entry]] (= :external (:ownership entry)))) @editors)
         restored
         (into {}
               (keep (fn [pane]
                       (let [id (:id pane)
                             reference (get reconnect (keyword id) (get reconnect id))
                             entry (or (get external id) (get owners (:sessionId reference)))]
                         (when (and entry (not= "closed" (:lifecycle (public-entry entry))))
                           (when (and (= :external (:ownership entry))
                                      (not (and (= (:executedSource pane) (:source entry))
                                                (= (:extensions pane) (:extensions entry)))))
                             (require-owned! entry))
                           [id entry]))))
               (:panes document))]
     (doseq [entry (vals @editors) :when (not (:managed entry))] (close-entry! entry))
     (reset! editors (merge external restored))
     {:generations (into {} (map (fn [[id entry]] [id (:generation entry)])) restored)
      :sessionIds (into {} (map (fn [[id entry]] [id (:sessionId entry)])) restored)
      :runIds (into {} (map (fn [[id entry]] [id (:runId (public-entry entry))])) restored)
      :sessions (into {} (map (fn [[id entry]] [id (public-entry entry)])) restored)
      :displayModes (into {} (keep (fn [[id entry]]
                                    (when-let [mode (:displayMode (public-entry entry))] [id mode]))) restored)})))

(defn detached-entry [owners session-id]
  (or (get @owners session-id) (throw (ex-info "Session not found; Run explicitly" {:sessionId session-id}))))

(defn detach! [editors owners {:keys [pane generation]}]
  (let [entry (require-pane editors pane)]
    (alter-meta! editors assoc ::service (assoc (service-state editors) :owners owners))
    (require-generation entry generation)
    (swap! owners assoc (:sessionId entry) entry)
    (swap! editors dissoc pane)
    {:sessionId (:sessionId entry)}))

(defn list-detached [owners]
  {:sessions (mapv public-entry (sort-by :sessionId (vals @owners)))})

(defn reopen! [editors owners {:keys [pane sessionId]}]
  (when-not (and (string? pane) (not (empty? pane))) (throw (ex-info "Pane id required" {})))
  (when (contains? @editors pane) (throw (ex-info "Target pane is occupied" {:pane pane})))
  (let [entry (detached-entry owners sessionId)]
    (alter-meta! editors assoc ::service (assoc (service-state editors) :owners owners))
    (swap! editors assoc pane entry)
    ;; Managed and external owners remain discoverable regardless of view count.
    (when-not (or (:managed entry) (= :external (:ownership entry))) (swap! owners dissoc sessionId))
    (public-entry entry)))

(defn stop-detached! [owners {:keys [sessionId generation]}]
  (let [entry (detached-entry owners sessionId)]
    (require-owned! entry)
    (when generation (require-generation entry generation))
    (close-entry! entry)
    (swap! owners dissoc sessionId)
    {:stopped sessionId}))

(defn stop-session! [editors command]
  (let [result (stop-detached! (owner-catalog editors) command)]
    (swap! editors (fn [views] (into {} (remove #(= (:sessionId command) (:sessionId (val %)))) views)))
    result))

(defn remove! [editors id]
  (when-let [entry (get @editors id)]
    (when-not (:managed entry) (close-entry! entry)))
  (swap! editors dissoc id)
  {:closed id})

(defn close!
  "Explicit service/test teardown stops owned sessions, including headless owners."
  [editors]
  (let [owners (:owners (::service (meta editors)))
        entries (concat (vals @editors) (when owners (vals @owners)))]
    (doseq [entry (vals (into {} (map (juxt :session identity)) entries))] (close-entry! entry))
    (reset! editors {})
    (when (and owners (not (identical? owners editors)))
      (swap! owners (fn [entries] (into {} (filter #(= :external (:ownership (val %)))) entries))))))
