(ns propagators.compiler-2.language.contract
  "Validate canonical syntax and derive declaration identity from syntax."
  (:require [propagators.compiler-2.language.ast :as ast]))

(defn children [expression]
  (case (ast/type expression)
    (:literal :symbol) []
    :sequence (ast/body expression)
    :let-cell [(ast/body expression)]
    :let (conj (mapv second (ast/bindings expression)) (ast/body expression))
    :when-topology [(ast/condition expression) (ast/body expression)]
    :def (if-let [body (ast/body expression)] [body] [])
    :apply (into [(ast/operator expression)] (ast/args expression))
    :network
    (if (nil? (ast/output expression))
      [(ast/body expression)]
      (throw (ex-info "Networks derive outputs from their body return"
                      {:output (ast/output expression)})))
    (:compound :def-net :def-constraint)
    (throw (ex-info "Construct is outside the functional-network core"
                    {:type (ast/type expression)}))
    ;; Custom CPS dispatchers own their additional expression kinds.
    []))

(defn declaration-key [expression]
  (loop [pending [(ast/ast expression)] syntax []]
    (if (seq pending)
      (let [node (peek pending)
            nested (children node)
            fields [(ast/type node) (ast/value node) (ast/name node)
                    (ast/names node) (ast/inputs node)
                    (if (= :let (ast/type node)) (mapv first (ast/bindings node)) [])
                    (count nested)]]
        (recur (into (pop pending) nested) (conj syntax fields)))
      syntax)))
