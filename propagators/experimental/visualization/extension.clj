(ns propagators.experimental.visualization.extension
  (:require [propagators.compiler-2.runtime.operators.retained-relationship :as retained]
            [propagators.compiler-2.runtime.session.extension :as session]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.operators.xr :as xr]
            [propagators.compiler-2.runtime.operators.graph-modifiers :as graph-modifiers]
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
               ['relationship:dataflow (retained/dataflow-operator)]
               ['relationship:roots (retained/roots-operator)]
               ['relationship:semantic (retained/semantic-operator)]
               ['relationship:roots-semantic (retained/roots-semantic-operator)]
               ['relationship:children (retained/children-operator)]
               ['relationship:with-values (graph-modifiers/with-values-operator)]
               ['relationship:caller-view (graph-modifiers/caller-view-operator)]
               ['xr:io (xr/xr-io-operator (runtime-ids/boundary-outbox-id))]]
    :effects []}))

(def ttms-extension
  (session/extension-bundle
   {:id ::ttms-collections
    :bindings (into (vec (remove #(contains? #{'relationship:roots 'relationship:dataflow 'relationship:semantic 'relationship:roots-semantic} (first %))
                                (session/extension-bindings extension)))
                    [['relationship:roots (retained/roots-operator)]
                     ['relationship:dataflow (retained/dataflow-operator)]
                     ['relationship:semantic (retained/semantic-operator)]
                     ['relationship:roots-semantic (retained/roots-semantic-operator)]])
    :effects []}))
