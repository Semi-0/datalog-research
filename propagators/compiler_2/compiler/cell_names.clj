(ns propagators.compiler-2.compiler.cell-names
  "Short role names for compiler-created cells; never used as identity."
  (:require [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.gur :as gur]))

(def source-scope :compiler-2/source-cells)

(defn source-name [expression]
  (case (ast/type expression)
    :literal "CONSTANT"
    "RESULT"))

(defn assign [network id expression]
  (let [named (declarations/ensure-cell network id (source-name expression))
        [_ published] (declarations/eval-activation-result (gur/bind-name source-scope id id) named)]
    published))
