(ns propagators.experimental.visualization.extension
  (:require [propagators.compiler-2.runtime.session.extension :as session]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.operators.xr :as xr]
            [propagators.compiler-2.runtime.operators.relationship-observer :as relationship]
            [propagators.experimental.visualization.collections :as collections]
            [propagators.experimental.visualization.observation :as observation]
            [propagators.experimental.visualization.presentation :as presentation]
            [propagators.experimental.visualization.zoom :as zoom]))

(def extension
  (session/extension-bundle
   {:id ::collections
    :bindings [['map (collections/collection-operator :map)]
               ['filter (collections/collection-operator :filter)]
               ['transpose (collections/transpose-operator)]
               ['inputs-of observation/inputs-of]
               ['outputs-of observation/outputs-of]
               ['occurrence-of observation/occurrence-of]
               ['member? observation/member?]
               ['strongest-of observation/strongest-of]
               ['focus zoom/focus]
               ['sources-of zoom/sources-of]
               ['juxtapose presentation/juxtapose]
               ['selectable presentation/selectable]
               ['relationship:dataflow (relationship/dataflow-operator)]
               ['relationship:roots (relationship/roots-operator)]
               ['xr:io (xr/xr-io-operator (runtime-ids/boundary-outbox-id))]]
    :effects []}))
