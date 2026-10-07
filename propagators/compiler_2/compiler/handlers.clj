(ns propagators.compiler-2.compiler.handlers
  "Active continuation-based compiler-2 handlers."
  (:require [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.compiler.declarations :as declarations]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.runtime.lazy-topology :as lazy-topology]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-common.cps :as cps]
            [propagators.compiler-common.core :as common]
            [propagators.network-builder :as nb]))

(defn- finish
  [k [state binding]]
  (cps/continue k state binding))

(defn compile-literal
  [_compile-k state expr k]
  (let [candidate (ast/value expr)]
    (let [[declared binding] (h/new-cell state :literal candidate)]
      (finish k (declarations/declare-operator-description declared binding candidate)))))

(defn- compile-accessed-symbol
  [state sym k]
  (let [binding-id (h/node-id state [:lexical-access sym :binding])
        value-id (h/node-id state [:lexical-access sym :value])
        network (-> (:net state)
                    (nb/ensure-cell binding-id)
                    (nb/ensure-cell value-id))
        [access-props with-access]
        ((env/p:lexical-access-local-first sym (:env state) binding-id) network)
        [value-props compiled]
        ((env/p:binding-value binding-id value-id) with-access)]
    (cps/continue k
                  (-> state
                      (assoc :net compiled)
                      (h/add-props (into (vec access-props) value-props)))
                  (env/cell-binding value-id))))

(defn- compile-free-symbol
  [state sym k]
  (let [binding-id (h/node-id state [:free-symbol sym])
        declared (-> state
                     (update :net nb/ensure-cell binding-id)
                     (declarations/reserve-fixed-local sym binding-id))]
    (cps/continue k declared (env/cell-binding binding-id))))

(defn- compile-found-symbol
  [state binding-id k]
  (let [candidate (h/strongest-or-nothing (:net state) binding-id)]
    (finish k (declarations/declare-operator-description
               state (env/cell-binding binding-id) candidate))))

(defn compile-symbol
  [_compile-k state expr k]
  (let [sym (ast/name expr)
        {:keys [status binding/id]}
        (env/lexical-binding-status (:net state) sym (:env state))]
    (case status
      :found (compile-found-symbol state id k)
      :missing (compile-free-symbol state sym k)
      (compile-accessed-symbol state sym k))))

(defn compile-sequence
  [compile-k state expr k]
  (cps/compile-seq compile-k state (ast/body expr) k))

(defn- let-initializer
  [[name value]]
  (cond
    (nil? value)
    nil

    :else
    (ast/app (ast/sym '->) value (ast/sym name))))

(defn compile-let
  [compile-k state expr k]
  (let [outer-env (:env state)
        bindings (ast/bindings expr)
        names (mapv first bindings)
        initializers (vec (keep let-initializer bindings))
        body (if (seq initializers)
               (apply ast/sequence* (concat initializers [(ast/body expr)]))
               (ast/body expr))
        [body-state _bindings]
        (declarations/declare-local-cells state :let-env names)]
    (cps/call compile-k body-state body
              (fn [state' binding]
                (cps/continue k (assoc state' :env outer-env) binding)))))

(defn compile-when-topology
  [compile-k state expr k]
  (let [base-path (:path state)]
    (cps/call
     compile-k (h/child state :condition) (ast/condition expr)
     (fn [state' condition-binding]
       (let [condition-id (env/binding-id condition-binding)]
         (when-not condition-id
           (throw (ex-info "when condition must compile to a cell"
                           {:condition condition-binding})))
         (finish k
                 (lazy-topology/install-when-topology-with
                  (:compiler state')
                  (assoc state' :path base-path)
                  condition-id
                  (ast/body expr))))))))

(defn compile-network
  [_compile-k state expr k]
  (finish k (declarations/declare-closure state
                                          (ast/inputs expr)
                                          (ast/output expr)
                                          (ast/body expr))))

(defn compile-def
  [compile-k state expr k]
  (let [body (ast/body expr)
        name (ast/name expr)
        path (:path state)]
    (cps/call
     compile-k (h/child state :definition-target) (ast/sym name)
     (fn [target-state target]
       (let [value-state (h/child (assoc target-state :path path) :definition-value)]
         (if body
           (cps/call compile-k value-state body
                     (fn [compiled source]
                       (finish k (declarations/declare-definition
                                  (assoc compiled :path path) name target source))))
           (let [[declared source] (h/new-cell value-state :unfilled)]
             (finish k (declarations/declare-definition
                        (assoc declared :path path) name target source)))))))))

(defn compile-operator
  [compile-k state expression continuation]
  (let [base-path (:path state)]
    (cps/call
     compile-k
     (h/child (common/with-path state base-path) :operator)
     (ast/operator expression)
     (fn [compiled-state operator-binding]
       (continuation
        (common/with-path compiled-state base-path)
        operator-binding)))))

(defn compile-argument-cells
  [compile-k state operand-forms continuation]
  (cps/compile-args compile-k state operand-forms continuation))

(defn- continue-with-application-topology
  [continuation application-context operator-binding fallback-result-id
   state argument-bindings]
  (let [[declared result-binding]
        (declarations/declare-application-topology
         (merge state application-context)
         operator-binding argument-bindings fallback-result-id)]
    (cps/continue continuation declared result-binding)))

(defn dispatch-application
  [compile-k continuation state operator-binding
   operand-forms fallback-result-id]
  (let [compiler-operands
        (operator-value/operator-compiler-operands
         (declarations/known-operator-declaration state operator-binding))
        application-context
        (select-keys state
                     [:context-id
                      :application/app-id
                      :application/operator-ast])]
    (if (fn? compiler-operands)
      (compiler-operands
       compile-k state operand-forms fallback-result-id continuation)
      (compile-argument-cells
       compile-k state operand-forms
       (partial continue-with-application-topology
                continuation application-context
                operator-binding fallback-result-id)))))

(defn compile-application
  [compile-k state expr k]
  (compile-operator
   compile-k state expr
   (fn [operator-state operator-binding]
     (let [[application-state fallback-result-id]
           (declarations/declare-application-context operator-state expr)]
       (dispatch-application
        compile-k k application-state operator-binding
        (ast/args expr) fallback-result-id)))))

(defn continue-with-cell [continuation state binding]
  (if (env/binding-id binding)
    (cps/continue continuation state binding)
    (throw (ex-info "Compiler handler did not return a cell" {:binding binding}))))
