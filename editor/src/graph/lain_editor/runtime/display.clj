(ns graph.lain-editor.runtime.display
  "A pane-local display sink using the same layered forwarding policy as ->."
  (:require [graph.lain-editor.stack.scene :as stack-scene]
            [propagators.semantic-trace.capture :as capture]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.network-history :as history]
            [propagators.gur :as gur]
            [propagators.network :as net]))

(def cell-id (gur/stable-node-id [:lain-editor :display]))

(defn payload
  "Read the current supported payload for display."
  [candidate]
  (data/payload candidate))

(defn- source-ids [arg-ids _fallback-id _context-id]
  (when-not (= 1 (count arg-ids))
    (throw (ex-info "display expects one result cell" {:argument-ids arg-ids})))
  (vec arg-ids))

(def extension
  (extension/extension-bundle
   {:id :graph.lain-editor.display/display
    :bindings [['display
                (operator/propagator-operator
                 {:name 'display
                  :input-selector source-ids
                  :output-selector (fn [_args _fallback-id] [cell-id])
                  :activate (fn [network inputs _outputs _context-id]
                              (basis/mono-sync-messages network (first inputs) cell-id))})]]
    :effects []}))

(defn mode [network]
  (when (contains? (net/net-env network) cell-id)
    (if (let [payload (payload (net/network-cell-strongest network cell-id))]
          (or (capture/semantic-graph? payload)
              (history/replay? payload)
              (stack-scene/stack-value? network payload)))
      "graph"
      "value")))
