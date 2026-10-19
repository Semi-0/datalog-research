(ns propagators.experimental.ttms-application.runtime
  "Compatibility entrypoint for the shared supported-callable runtime."
  (:require [propagators.compiler-2.runtime.supported-application.runtime :as runtime]))

(def application-effects runtime/application-effects)
