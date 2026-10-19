(ns propagators.experimental.ttms-application.ports
  "Compatibility access to supported-callable invocation planning."
  (:require [propagators.compiler-2.runtime.supported-application.ports :as ports]))

(def candidate-key ports/candidate-key)
(def plan ports/plan)
