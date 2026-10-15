(ns graph.vijual.layout.nodes
  "Continuous size-aware layout and persistent incremental sessions."
  (:require [graph.vijual.layout.node-model :as model]
            [graph.vijual.layout.stress-session :as session]))

(def default-options model/default-options)
(def create-layout session/create-layout)
(def update-layout session/update-layout)
(def advance-layout session/advance-layout)

(defn layout-flow
  "Optional core.async operator. Commands contain :changes and an explicit
  :iterations budget (default 0). Invalid commands retain the last valid state.
  Loading core.async is deferred until this adapter is requested."
  ([state] (layout-flow state 8))
  ([state buffer-size]
   ((requiring-resolve 'graph.vijual.layout.stress-flow/layout-flow) state buffer-size)))

(defn layout-nodes
  "Lay out nodes connected by [id id] edges; return a vector in input order,
  preserving other data. Defaults accept {:id id :position [x y] :size [w h]}.
  Positions are rectangle CENTERS, in the same units as size, :gap (10) and
  :edge-length (80). Custom structs use :id-fn, :position-fn, :size-fn and
  :with-position (node, position). Options include :iterations (100),
  :tolerance (0), :pinned-ids (#{}) and :disconnected-weight (0.02).

  Graph shortest paths and circumscribed node radii set soft distance targets;
  this is not guaranteed collision-free packing. No grid snapping or routing.
  Zero iterations preserves input nodes. Use create/update/advance-layout to
  retain preparation and numerical contributions across edits."
  ([nodes edges] (layout-nodes nodes edges {}))
  ([nodes edges opts]
   (:nodes (advance-layout (create-layout nodes edges opts)))))
