(ns graph.lain-editor.development.runtime
  "Live, opt-in premise-versioned sessions for editor development panes."
  (:require [graph.lain-editor.stack.checkpoint :as checkpoint]
            [graph.lain-editor.stack.index :as constraint-index]
            [graph.lain-editor.development.protocol :as protocol]
            [graph.lain-editor.development.session :as development-session]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.tui.block-model :as block-model])
  (:import [java.util UUID]))

(defn- generation [] (str (UUID/randomUUID)))

(defn- close-session! [session]
  (when session
    (runtime/stop-clocks! session)))

(defn- elapsed-ns [started]
  (- (System/nanoTime) started))

(defn- current-premise-id [state client-id index]
  (some-> (block-model/block-by-index state client-id index)
          :version-history vec peek :premise-id))

(defn- request-deltas [{:keys [outcomeUpdates evidenceUpdates] :as request}]
  (cond-> {:outcome-updates (mapv #(update % :op keyword) (or outcomeUpdates []))
           :evidence-updates (mapv #(update % :op keyword) (or evidenceUpdates []))}
    (contains? request :membershipComplete)
    (assoc :membership-complete? (:membershipComplete request))))

(defn require-entry [registry pane]
  (or (get @registry pane)
      (throw (ex-info "Development session is not started" {:pane pane}))))

(defn require-generation [entry supplied]
  (when-not (= supplied (:generation entry))
    (throw (ex-info "Development session changed; restart or refresh before retrying"
                    {:stale true :expected (:generation entry) :actual supplied})))
  entry)

(defn start!
  "Atomically replay a portable instance before replacing the pane's old session."
  [registry {:keys [pane model outcomes membershipComplete] :as request}]
  (protocol/start-request request)
  (let [candidate (development-session/new-session)
        started (System/nanoTime)]
    (try
      (let [replay (runtime/import-instance! candidate (:instance model))
            constraint-index (constraint-index/create
                              (keyword (:specificationRevision model))
                              (or outcomes []) membershipComplete [])
            entry {:session candidate
                   :generation (generation)
                   :specification-revision (keyword (:specificationRevision model))
                   :constraint-index constraint-index
                   :started-ns (- (System/nanoTime) started)}
            previous (get @registry pane)]
        (swap! registry assoc pane entry)
        (when previous (close-session! (:session previous)))
        {:generation (:generation entry)
         :specificationRevision (:specificationRevision model)
         :replay (select-keys replay [:status :commit-count])
         :view (runtime/read-tui-view @candidate {:client-id (:clientId model)})
         :instance (runtime/export-instance @candidate)})
      (catch Throwable error
        (close-session! candidate)
        (throw error)))))

(defn commit!
  [registry {:keys [pane generation commitId clientId index expectedVersion text] :as request}]
  (protocol/commit-request request)
  (locking registry
    (let [entry (require-generation (require-entry registry pane) generation)
          deltas (request-deltas request)
          ;; Validate all explicit deltas before mutating the runtime session.
          _ (constraint-index/apply-deltas (:constraint-index entry) deltas)
          previous-premise (current-premise-id @(:session entry) clientId index)
          commit-started (System/nanoTime)
          receipt (runtime/commit-version!
                   (:session entry)
                   {:commit-id commitId :client-id clientId :index index
                    :expected-version expectedVersion :text text})
          commit-ns (elapsed-ns commit-started)
          invalidation (if (:replayed? receipt)
                         {:index (:constraint-index entry)
                          :invalidated-outcomes [] :invalidated-evidence []}
                         (constraint-index/invalidate-premise
                          (:constraint-index entry) previous-premise))
          maintenance-started (System/nanoTime)
          next-index (if (:replayed? receipt)
                       (:index invalidation)
                       (constraint-index/apply-deltas (:index invalidation) deltas))
          maintenance-ns (elapsed-ns maintenance-started)
          next-generation (if (:replayed? receipt) generation (graph.lain-editor.development.runtime/generation))
          next-entry (assoc entry :generation next-generation
                                  :constraint-index next-index)
          profile {:commit-total-ns commit-ns
                   :constraint-maintenance-ns maintenance-ns
                   :runtime-profile (:runtime/last-incremental-profile @(:session entry))
                   :changed-outcomes (constraint-index/changed-outcomes
                                      (:constraint-index entry) next-index)
                   :invalidated-outcomes (:invalidated-outcomes invalidation)
                   :invalidated-evidence (:invalidated-evidence invalidation)}]
      (swap! registry assoc pane next-entry)
      {:generation next-generation
       :specificationRevision (name (:specification-revision next-entry))
       :receipt receipt
       :profile profile
       :view (runtime/read-tui-view @(:session next-entry) {:client-id clientId})
       :instance (runtime/export-instance @(:session next-entry))})))

(defn checkpoint!
  [registry {:keys [pane generation fixtureExpectations]}]
  (let [entry (require-generation (require-entry registry (protocol/validate-pane! pane))
                                  (protocol/validate-generation! generation))
        captured (checkpoint/checkpoint
                  {:id (keyword (str "development-" (UUID/randomUUID)))
                   :specification-revision (:specification-revision entry)
                   :state @(:session entry)
                   :outcomes (constraint-index/outcomes (:constraint-index entry))
                   :membership-complete? (get-in entry [:constraint-index :membership-complete?])
                   :fixture-expectations (or fixtureExpectations {})
                   :diagnostics [] :evidence (constraint-index/evidence (:constraint-index entry))
                   :timing {:session-start-ns (:started-ns entry)}})]
    {:generation generation
     :checkpoint (checkpoint/portable-summary captured)}))

(defn export [registry {:keys [pane generation]}]
  (let [entry (require-generation (require-entry registry (protocol/validate-pane! pane))
                                  (protocol/validate-generation! generation))]
    {:generation generation
     :specificationRevision (name (:specification-revision entry))
     :instance (runtime/export-instance @(:session entry))}))

(defn close-pane! [registry pane]
  (protocol/validate-pane! pane)
  (when-let [entry (get @registry pane)]
    (close-session! (:session entry))
    (swap! registry dissoc pane))
  {:closed pane})

(defn close! [registry {:keys [pane generation]}]
  (require-generation (require-entry registry (protocol/validate-pane! pane))
                      (protocol/validate-generation! generation))
  (close-pane! registry pane))

(defn close-all! [registry]
  (doseq [entry (vals @registry)] (close-session! (:session entry)))
  (reset! registry {}))
