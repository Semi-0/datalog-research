(ns graph.lain-editor.display
  "A pane-local display sink using the same layered forwarding policy as ->."
  (:require [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.network-history :as history]
            [propagators.datastructures.event :as event]
            [propagators.gur :as gur]
            [propagators.network :as net]
            [propagators.semantic-trace :as trace]))

(def cell-id (gur/stable-node-id [:lain-editor :display]))

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
    (if (let [payload (data/payload (net/network-cell-strongest network cell-id))]
          (or (trace/semantic-trace-graph? payload) (history/replay? payload)))
      "graph"
      "value")))
