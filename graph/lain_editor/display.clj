(ns graph.lain-editor.display
  "A pane-local display sink using the same layered forwarding policy as ->."
  (:require [graph.lain-editor.stack-scene :as stack-scene]
            [propagators.semantic-trace.capture :as capture]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.network-history :as history]
            [propagators.datastructures.event :as event]
            [propagators.gur :as gur]
            [propagators.network :as net]))

(def cell-id (gur/stable-node-id [:lain-editor :display]))

(defn payload
  "Project one current event value for display without changing retained event content."
  [candidate]
  (let [value (data/payload candidate)]
    (if (event/event-projection? value)
      (let [values (distinct (vals (event/active-values value)))]
        (if (= 1 (count values)) (first values) value))
      value)))

(defn- source-ids [arg-ids _fallback-id _context-id]
  (when-not (= 1 (count arg-ids))
    (throw (ex-info "display expects one result cell" {:argument-ids arg-ids})))
  (vec arg-ids))

(def extension
  (extension/extension-bundle
   {:id ::display
    :bindings [['display
                (operator/propagator-operator
                 {:name 'display
                  :prepare-network (fn [network {:keys [inputs outputs]}]
                                     (if (some #(event/protocol-cell? network %) inputs)
                                       (reduce event/mark-protocol-cell network outputs)
                                       network))
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
