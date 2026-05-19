(ns propagators.stdlib
  "Built-in propagator installers."
  (:require [propagators.network :refer [primitive-propagator]]))

(def p:id (primitive-propagator (fn [x] x)))
