(ns propagators.experimental.ttms-application
  "Compatibility extension for the explicit ttms-premise-closure constructor.
  Supported callable application is shared with the ordinary compiler runtime."
  (:require [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.supported-application.runtime]
            [propagators.experimental.ttms-primitives :as primitives]
            [propagators.propagator :as prop]))

(def session-extension
  (extension/extension-bundle
   {:id ::native-premise-closures
    :bindings [['ttms-premise-closure
                (primitives/scalar-operator
                 ::premise-closure
                 (prop/primitive-propagator ::premise-closure
                                            (fn [procedure _definition-source] procedure)))]]
    :effects []}))
