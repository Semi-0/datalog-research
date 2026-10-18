(ns graph.lain-editor.semantic-capture
  "Pane-owned retention and refinement of original frozen observations."
  (:require [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.merge :as merge]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.stdlib.premise-state :as state]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.runner :as runner]))

(def storage-key ::sources)

(defn storage
  [session]
  (or (get @session storage-key)
      (get-in @session [:runtime/options storage-key])
      (locking session
        (or (get @session storage-key)
            (let [sources (atom {})]
              (swap! session assoc storage-key sources)
              sources)))))

(defn retain!
  ([session observation-id source]
   (retain! session observation-id source nil))
  ([session observation-id source captured]
   (swap! (storage session)
          #(if (contains? % observation-id)
             %
             (assoc % observation-id
                    (cond-> {:source source}
                      captured (assoc :capture captured)))))
   observation-id))

(defn retained
  [session observation-id]
  (get @(storage session) observation-id))

(defn close!
  [session]
  (when-let [sources (or (get @session storage-key)
                         (get-in @session [:runtime/options storage-key]))]
    (reset! sources {}))
  (swap! session #(-> % (dissoc storage-key) (update :runtime/options dissoc storage-key))))

(defn refine
  "Acquire another fragment from the original frozen adapter."
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
        inherited)
      (capture/with-diagnostic
       known
       (or (:scope request) :root)
       {:kind :missing
        :message "The original frozen observation is no longer available"
        :observation observation-id}))))

(defn publish-refinement!
  "Submit another fragment through the ordinary merge propagator."
  [session known request]
  (locking session
    (let [observation-id (capture/observation-id known)
          entry (retained session observation-id)
          output (or (:publisher entry)
                     (ids/new-node-id))
          network (:program/net @session)
          inbox (merge/inbox-id output)
          existing (when (and network (contains? (net/net-env network) inbox))
                     (merge/joined-observation network inbox observation-id))
          current (or existing known)]
      (if (capture/scope current (:scope request))
        current
        (let [acquired (refine session known request)
              original (when (:publisher entry)
                         (net/network-cell-strongest
                          network
                          (merge/fragment-cell-id inbox [observation-id :root])))
              dependencies (support/combine (capture/support acquired)
                                             (datum/support-of original))
              evidence (merge/fragment-evidence acquired dependencies (state/states-of original))
              seed (when-not existing
                     (merge/submit output [observation-id :root]
                                   (merge/fragment-evidence current (capture/support current) #{})))
              [tasks submitted] (declarations/eval-activation-result
                                 [seed (merge/submit output [observation-id (:scope request)] evidence)]
                                 (or network net/empty-net))
              settled (runner/completed-network (runner/run-network tasks submitted))
              refined (merge/joined-observation settled inbox observation-id)]
          (swap! session assoc :program/net settled)
          (or refined acquired))))))
