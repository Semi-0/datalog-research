(ns propagators.experimental.ttms-application
  "Opt-in supported callable application. Requiring this namespace registers the
  Net handler; compiler options and production distributed-TMS bindings do not change."
  (:require [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.ttms-application.runtime]
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
