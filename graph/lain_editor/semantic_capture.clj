(ns graph.lain-editor.semantic-capture
  "Pane-owned retention and refinement of original frozen observations."
  (:require [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.session-runner :as session-runner]
            [propagators.semantic-trace.observation :as observation]
            [propagators.cell-evaluator :as evaluator]
            [propagators.gur :as gur]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as builder]
            [propagators.runner :as runner]))

(def storage-key ::sources)

(defn storage
  [session]
  (or (get @session storage-key)
      (locking session
        (or (get @session storage-key)
            (let [sources (atom {})]
              (swap! session assoc storage-key sources)
              sources)))))

(defn retain!
  ([session observation-id source]
   (retain! session observation-id source nil))
  ([session observation-id source captured]
   (swap! (storage session) assoc observation-id
          (cond-> {:source source}
            captured (assoc :capture captured)))
   observation-id))

(defn retained
  [session observation-id]
  (get @(storage session) observation-id))

(defn close!
  [session]
  (when-let [sources (get @session storage-key)]
    (reset! sources {}))
  (swap! session dissoc storage-key))

(defn refine
  "Acquire one scope from the retained original and join it into `known`."
  [session known request]
  (let [observation-id (capture/observation-id known)
        entry (retained session observation-id)
        source (if (map? entry) (:source entry) entry)]
    (if source
      (let [acquired (capture/capture-fragment
                      source (assoc request :observation observation-id :refinement :on-demand))
            modifiers (:graph/modifiers (capture/scope known :root))
            inherited (if (seq modifiers)
                        (capture/update-scope acquired (:scope request)
                                              #(assoc % :graph/modifiers modifiers))
                        acquired)]
        (capture/join known inherited nil))
      (capture/with-diagnostic
       known
       (or (:scope request) :root)
       {:kind :missing
        :message "The original frozen observation is no longer available"
        :observation observation-id}))))

(defn publish-refinement!
  "Accumulate original-source refinement in a cell, then publish a new version.
  Earlier recorded values remain immutable. The owning Net selects its runner."
  [session known request]
  (locking session
    (let [observation-id (capture/observation-id known)
          entry (retained session observation-id)
          known (if-let [existing (:capture entry)] (capture/join existing known nil) known)]
      (if (capture/scope known (:scope request))
        known
        (let [refined (refine session known request)
              publisher (:publisher entry)]
          (when publisher
            (let [id (gur/stable-node-id [::accumulation observation-id])
                  network (builder/ensure-cell (:program/net @session) id)
                  evaluate (evaluator/make-cell-evaluator (session-runner/combined-capture-merge))
                  [_ seeded] (evaluate (message/message id known) network)
                  [_ accumulated] (evaluate (message/message id refined) seeded)
                  captured (net/network-cell-strongest accumulated id)
                  previous (net/network-cell-strongest accumulated publisher)
                  [tasks published] (evaluate (observation/publication-message publisher previous captured)
                                              accumulated)
                  settled (runner/completed-network (runner/run-network tasks published))]
              (swap! session assoc :program/net settled)))
          (when entry
            (swap! (storage session) assoc observation-id (assoc entry :capture refined)))
          refined)))))
