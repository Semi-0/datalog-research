(ns propagators.experimental.ttms-application.publication
  "Compatibility access to supported-callable publication."
  (:require [propagators.compiler-2.runtime.supported-application.publication :as publication]))

(def base publication/base)
(def active? publication/active?)
(def feedback-scope publication/feedback-scope)
(def own-feedback? publication/own-feedback?)
(def publisher publication/publisher)
(def boundary publication/boundary)
