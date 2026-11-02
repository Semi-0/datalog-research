(ns propagators.compiler-2.fixtures.injected-module
  (:require [propagators.compiler-2.session.operators :as operators]))

(defn bindings [interpreter]
  [['module-later
    (operators/async-op interpreter
      (fn [make]
        (make (fn [n]
                (operators/effect-output (operators/next-round-input (inc n)))))))]])
