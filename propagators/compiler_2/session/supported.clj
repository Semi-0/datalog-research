(ns propagators.compiler-2.session.supported
  "Opt-in definition support, composed with the ordinary CPS compiler and runner."
  (:require [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.compiler.handlers :as handlers]
            [propagators.compiler-2.compiler.declarations :as declarations]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.compiler.predicates :as predicates]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-common.cps :as cps]
            [propagators.experimental.ttms-primitives :as primitives]
            [propagators.propagator :as prop]))

(def gate
  (primitives/scalar-operator ::definition-support
    (prop/primitive-propagator ::definition-support (fn [v _support] v))))

(defn compiler [source-id]
  (let [install (operator/operator-install gate)
        supported-handler
        (fn [handler]
          (fn [compile-k state expression k]
            (handler compile-k state expression
              (fn [compiled raw]
                (let [[prepared output] (h/new-cell compiled ::supported-value)
                      [network props selected]
                      (install (:net prepared) [(env/binding-id raw) source-id]
                               (env/binding-id output))]
                  (cps/continue k (-> prepared (assoc :net network) (h/add-props props))
                                (env/cell-binding selected)))))))
        symbol-handler (supported-handler handlers/compile-symbol)]
    (cps/make-compiler
      (cps/compose-rules
        (cps/on predicates/network? (supported-handler handlers/compile-network))
        (cps/on predicates/literal? (supported-handler handlers/compile-literal))
        (cps/on predicates/symbol?
          (fn [compile-k state expression k]
            ;; Preserve argument-cell identity, especially writable ports.
            (if (and (= :operator (peek (:path state)))
                     (not (#{'-> '<->} (ast/name expression))))
              (symbol-handler compile-k state expression k)
              (handlers/compile-symbol compile-k state expression k))))
        (cps/on predicates/definition?
          (fn [compile-k state expression k]
            (let [path (:path state) name (ast/name expression) body (ast/body expression)]
              (if-not body
                (handlers/compile-def compile-k state expression k)
                (cps/call compile-k (h/child state :definition-target) (ast/sym name)
                  (fn [target-state target]
                    ((supported-handler
                       (fn [compile-k state body k] (cps/call compile-k state body k)))
                     compile-k (h/child (assoc target-state :path path) :definition-value) body
                     (fn [compiled source]
                       (let [[declared result] (declarations/declare-definition
                                               (assoc compiled :path path) name target source)]
                         (cps/continue k declared result))))))))))
        compiler/compiler-dispatch))))
