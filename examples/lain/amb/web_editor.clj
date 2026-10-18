(ns examples.lain.amb.web-editor
  "Opt-in primitive bindings loaded through the editor's session boundary."
  (:require [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.amb.learning-view :as view]
            [propagators.experimental.amb.operators :as amb]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.propagator :as prop]))

(def learning-view-operator
  (ttms/scalar-operator
   'amb:learning-view
   (prop/concrete-primitive-propagator :amb/learning-view view/learning-view)))

(defn primitive-bindings []
  (conj (into (extension/extension-bindings ttms/session-extension)
              (extension/extension-bindings amb/session-extension))
        ['amb:learning-view learning-view-operator]))
