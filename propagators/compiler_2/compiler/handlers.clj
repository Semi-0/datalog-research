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
            [propagators.gur.accumulating.core :as gur-core]
            [propagators.ids :as ids]))

(defn- finish
  [k [state binding]]
  (cps/continue k state binding))

(defn compile-literal
  [_compile-k state expr k]
  (let [literal (ast/value expr)
        [declared binding] (h/new-cell state :literal literal)
        binding-id (env/binding-id binding)
        declared
        (cond
          (operator-value/operator-closure? literal)
          (update declared
                  :net
                  env/retain-compiler-declaration
                  binding-id
                  literal)

          :else
          declared)]
    (finish k [declared binding])))

(defn- compile-accessed-symbol
  [state sym k]
  (let [binding-id (h/node-id state [:lexical-access sym :binding])
        value-id (h/node-id state [:lexical-access sym :value])
        network (-> (:net state)
                    (h/ensure-cell binding-id)
                    (h/ensure-cell value-id))
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
                     (update :net h/ensure-cell binding-id)
                     (declarations/reserve-fixed-local sym binding-id))]
    (cps/continue k declared (env/cell-binding binding-id))))

(defn compile-symbol
  [_compile-k state expr k]
  (let [sym (ast/name expr)
        {:keys [status binding/id]}
        (env/lexical-binding-status (:net state) sym (:env state))]
    (case status
      :found (cps/continue k state (env/cell-binding id))
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

(defn- install-accumulating-when
  [state condition-id body]
  (let [[state' result-binding] (h/new-cell state :when-result)
        frame-context (:gur/frame-context state')
        applied-net-id (:applied-net-id frame-context)
        compile* (:compiler state')]
    (cond
      (not (ids/node-id? applied-net-id))
      (throw (ex-info "Compiler 2 GUR frame is missing its accumulated subnet"
                      {:frame-context frame-context}))

      (not (fn? compile*))
      (throw (ex-info "Compiler 2 GUR frame is missing its compiler"
                      {:frame-context frame-context}))

      :else
      (let [declare-body (fn [runtime-net]
                           (lazy-topology/declare-body compile*
                                                       state'
                                                       body
                                                       runtime-net))
            [prop-id network]
            ((gur-core/p:when-declaration condition-id
                                          applied-net-id
                                          declare-body)
             (:net state'))]
        [(-> state'
             (assoc :net network)
             (h/add-props [prop-id]))
         result-binding]))))

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
         (let [prepared (assoc state' :path base-path)
               installed
               (cond
                 (map? (:gur/frame-context prepared))
                 (install-accumulating-when prepared
                                            condition-id
                                            (ast/body expr))

                 (nil? (:gur/frame-context prepared))
                 (lazy-topology/install-when-topology-with
                  (:compiler prepared)
                  prepared
                  condition-id
                  (ast/body expr))

                 :else
                 (throw (ex-info "invalid Compiler 2 GUR frame context"
                                 {:frame-context
                                  (:gur/frame-context prepared)})))]
           (finish k installed)))))))

(defn compile-network
  [_compile-k state expr k]
  (finish k (declarations/declare-closure state
                                          (ast/inputs expr)
                                          (ast/output expr)
                                          (ast/body expr))))

(defn compile-def
  [compile-k state expr k]
  (let [body (ast/body expr)
        name (ast/name expr)]
    (cond
      (nil? body)
      (let [[state' binding] (h/new-cell state [:def name])]
        (finish k (declarations/define-binding state' name binding)))

      (= :network (ast/type body))
      (let [[state' binding]
            (declarations/declare-closure state name
                                          (ast/inputs body)
                                          (ast/output body)
                                          (ast/body body))]
        (finish k (declarations/define-binding state' name binding)))

      :else
      (cps/call
       compile-k (h/child state :body) body
       (fn [state' body-binding]
         (finish k (declarations/define-binding state' name body-binding)))))))

(defn- declare-compiled-application
  [compile* operator-binding arg-bindings state out-id]
  (cond
    (or (operator-value/operator-closure? operator-binding)
        (fn? operator-binding))
    (declarations/declare-operator-application-bindings
     compile* operator-binding arg-bindings state out-id)

    (env/binding-id operator-binding)
    (declarations/declare-runtime-cell-application-bindings
     compile* operator-binding arg-bindings state out-id)

    :else
    (throw (ex-info "application operator is not callable"
                    {:operator operator-binding}))))

(defn- retained-operator-declaration
  "Use inspectable compiler declaration data without reading the operator cell."
  [binding state]
  (let [binding-id (env/binding-id binding)
        declaration
        (cond
          (ids/node-id? binding-id)
          (env/compiler-declaration (:net state) binding-id)

          :else
          nil)]
    (cond
      (some? declaration)
      declaration

      :else
      binding)))

(defn compile-application
  [compile-k state expr k]
  (let [base-path (:path state)
        op (ast/operator expr)
        operand-forms (ast/args expr)]
    (cps/call
     compile-k (h/child (common/with-path state base-path) :operator) op
     (fn [state' op-binding]
       (let [state' (common/with-path state' base-path)
             [state'' operator-binding out-id]
             (common/prepare-application retained-operator-declaration
                                         state' op op-binding)
             compile* (:compiler state'')
             direct-compiler
             (and (operator-value/operator-closure? operator-binding)
                  (operator-value/operator-direct-compiler operator-binding))]
         (cond
           direct-compiler
           (direct-compiler compile-k state'' operand-forms out-id k)

           (and (operator-value/operator-closure? operator-binding)
                (operator-value/operator-direct-installer operator-binding))
           (finish k (declarations/declare-direct-operator-application
                      compile* operator-binding operand-forms state'' out-id))

           :else
           (cps/compile-args
            compile-k state'' operand-forms
            (fn [state''' arg-bindings]
              (finish k (declare-compiled-application
                         compile* operator-binding arg-bindings
                         (merge state'''
                                (select-keys state''
                                             [:context-id
                                              :application/app-id
                                              :application/operator-ast]))
                         out-id))))))))))
