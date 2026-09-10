(ns propagators.compiler-2.compiler.declarations
  "Pure compiler-2 declarations, separate from traversal strategy."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.application :as compiler-app]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.compiler.dispatch :as dispatch]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.operators.call-graph :as call-graph]
            [propagators.compiler-common.cps :as cps]
            [propagators.compiler-common.core :as common]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur :as gur]
            [propagators.gur.accumulating.core :as gur-core]
            [propagators.gur.accumulating.facts :as facts]
            [propagators.ids :as ids]
            [propagators.stdlib.prop :as stdlib-prop]))

(defn- install-call-publisher
  [state app-id operator-id]
  (if-let [caller-id (:application/caller state)]
    (let [[prop-id network]
          ((call-graph/p:application-call caller-id app-id operator-id)
           (:net state))]
      (-> state
          (assoc :net network)
          (h/add-props [prop-id])))
    state))

(defn- record-application
  [state app-id operator-ast operator-id result-id context-id]
  (-> (common/record-application-ir state app-id operator-ast operator-id
                                    result-id context-id)
      (install-call-publisher app-id operator-id)))

(defn- install-application-propagator
  [state app-id operator-ast operator-id result-id context-id operator]
  (let [recorded (common/record-application-ir state app-id operator-ast
                                                operator-id result-id context-id)
        caller-id (:application/caller state)
        graph-id (cond
                   (ids/node-id? caller-id)
                   (call-graph/graph-cell-id caller-id)

                   :else
                   nil)
        declaration-cell-ids
        (vec (filter ids/node-id? [context-id graph-id]))
        operator-boundary-output-ids
        (cond
          (gur-core/recursive-closure? operator)
          (gur-core/projected-boundary-output-cell-ids
           operator
           (:net recorded)
           (:application/arg-ids recorded))

          :else
          [])
        declaration-output-cell-ids
        (vec (distinct
              (concat
               (cond
                 (ids/node-id? graph-id)
                 [graph-id]

                 :else
                 [])
               operator-boundary-output-ids)))
        prepared-network
        (reduce h/ensure-cell
                (:net recorded)
                declaration-output-cell-ids)
        declared-network
        (facts/declare-application prepared-network
                                   operator-id
                                   (:application/arg-ids recorded)
                                   result-id
                                   {:application-id app-id
                                    :context-id context-id
                                    :cell-ids declaration-cell-ids
                                    :output-cell-ids
                                    declaration-output-cell-ids})
        [prop-ids network]
        ((gur/p:apply-closure operator-id
                              (:application/arg-ids recorded)
                              result-id)
         declared-network)]
    (-> recorded
        (assoc :net network)
        (h/add-props prop-ids)
        (install-call-publisher app-id operator-id))))

(defn declare-direct-operator-application
  [compile* operator-binding operand-forms state out-id]
  ((operator-value/operator-direct-installer operator-binding)
   (assoc state :compiler compile*) operand-forms out-id))

(defn- argument-ids
  [arg-bindings]
  (let [arg-ids (mapv env/binding-id arg-bindings)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "application arguments must compile to cells"
                      {:args arg-bindings})))
    arg-ids))

(defn declare-operator-application-bindings
  [compile* operator-binding arg-bindings state out-id]
  (let [app-id (:application/app-id state)
        operator-ast (:application/operator-ast state)
        context-id (:context-id state)
        arg-ids (argument-ids arg-bindings)]
    (let [[state'' args-binding] (common/install-argument-object state arg-ids)
          args-id (env/binding-id args-binding)
          [state''' operator-binding'] (common/install-operator-object state''
                                                                       operator-binding)
          operator-id (env/binding-id operator-binding')
          result-id (h/output-id operator-binding arg-ids out-id)
          prepared (assoc state'''
                          :application/args-id args-id
                          :application/arg-ids arg-ids)]
      [(install-application-propagator prepared app-id operator-ast
                                       operator-id result-id context-id
                                       operator-binding)
       (env/cell-binding result-id)])))

(defn declare-operator-application
  [compile* operator-binding operand-forms state out-id]
  (let [[state' arg-bindings] (common/compile-args compile* state operand-forms)]
    (declare-operator-application-bindings compile* operator-binding
                                           arg-bindings state' out-id)))

(defn- closure-output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else []))

(defn- hidden-return-symbol
  [state]
  (closure-value/implicit-return-symbol
   (ids/unwrap-node-id (h/node-id state :implicit-return))))

(defn- route-body-result
  [body output-sym]
  (let [route #(ast/app (ast/sym '->) % (ast/sym output-sym))]
    (if (= :sequence (ast/type body))
      (let [forms (vec (ast/body body))]
        (apply ast/sequence*
               (concat (butlast forms)
                       [(route (last forms))])))
      (route body))))

(defn normalize-closure-output
  [return-sym output body]
  (let [outputs (closure-output-symbols output)]
    (cond
      (nil? output)
      {:output [return-sym]
       :body (route-body-result body return-sym)}

      (= 1 (count outputs))
      {:output output
       :body (route-body-result body (first outputs))}

      :else
      {:output output
       :body body})))

(defn- implicit-return-route-source
  [expr return-sym]
  (when (and (= :apply (ast/type expr))
             (= '-> (ast/name (ast/operator expr)))
             (= 2 (count (ast/args expr)))
             (= return-sym (ast/name (second (ast/args expr)))))
    (first (ast/args expr))))

(defn closure-semantic-body
  "Return a closure body without its compiler-generated implicit-return route."
  [closure-info]
  (let [body (closure-value/closure-body closure-info)
        output (closure-value/closure-output closure-info)]
    (if-not (closure-value/implicit-return-output? output)
      body
      (let [return-sym (first output)]
        (if (= :sequence (ast/type body))
          (let [forms (vec (ast/body body))]
            (if-let [source (implicit-return-route-source (peek forms)
                                                          return-sym)]
              (apply ast/sequence* (conj (pop forms) source))
              body))
          (or (implicit-return-route-source body return-sym)
              body))))))

(defn declare-runtime-cell-application-bindings
  [compile* operator-binding arg-bindings state out-id]
  (let [app-id (:application/app-id state)
        operator-ast (:application/operator-ast state)
        context-id (:context-id state)
        arg-ids (argument-ids arg-bindings)]
    (let [operator-id (env/binding-id operator-binding)
          [state'' args-binding] (common/install-argument-object state arg-ids)
          args-id (env/binding-id args-binding)
          result-id out-id]
      [(-> state''
           (assoc :application/args-id args-id
                  :application/arg-ids arg-ids)
           (install-application-propagator app-id operator-ast
                                           operator-id result-id context-id nil))
       (env/cell-binding result-id)])))

(defn declare-runtime-cell-application
  [compile* operator-binding operand-forms state out-id]
  (let [[state' arg-bindings] (common/compile-args compile* state operand-forms)]
    (declare-runtime-cell-application-bindings compile* operator-binding
                                               arg-bindings state' out-id)))

(defn apply-operator
  [compile* operator-binding operand-forms _calling-env state out-id]
  (cond
    (and (operator-value/operator-closure? operator-binding)
         (operator-value/operator-direct-installer operator-binding))
    (declare-direct-operator-application compile* operator-binding
                                         operand-forms state out-id)

    (or (operator-value/operator-closure? operator-binding)
        (fn? operator-binding))
    (declare-operator-application compile* operator-binding operand-forms
                                  state out-id)

    (env/binding-id operator-binding)
    (declare-runtime-cell-application compile* operator-binding operand-forms
                                      state out-id)

    :else
    (throw (ex-info "application operator is not callable"
                    {:operator operator-binding}))))

(defn- install-env-topology
  [state installer]
  (let [[prop-ids network] (installer (:net state))]
    (-> state
        (assoc :net network)
        (h/add-props prop-ids))))

(defn declare-child-environment
  [state role local-names]
  (let [child-id (h/node-id state role)
        state' (update state :net h/ensure-cell child-id)]
    [(-> state'
         (install-env-topology
          (env/p:scope-frame (:env state) child-id local-names))
         (assoc :env child-id))
     child-id]))

(defn declare-local
  [state sym binding-id]
  (install-env-topology state (env/p:declare-local sym (:env state) binding-id)))

(defn declare-fixed-local
  [state sym binding-id]
  (install-env-topology state
                        (env/p:declare-canonical-local sym (:env state)
                                                       binding-id)))

(defn reserve-fixed-local
  [state sym binding-id]
  (install-env-topology state
                        (env/p:reserve-canonical-local sym (:env state)
                                                       binding-id
                                                       (:seed state))))

(defn declare-local-cells
  [state role names]
  (let [[state' child-id] (declare-child-environment state role names)]
    (reduce
     (fn [[state bindings] name]
       (let [binding-id (h/stable-node-id :compiler-2 :binding child-id name)
             state' (-> state
                        (update :net h/ensure-cell binding-id)
                        (reserve-fixed-local name binding-id))]
         [state' (assoc bindings name (env/cell-binding binding-id))]))
     [state' {}]
     names)))

(defn ^:deprecated closure-locals
  [name closure-id]
  (if name {name (env/compound-binding closure-id)} {}))

(defn ^:deprecated seed-closure-declaration
  [state closure-id closure-env-id lexical-env closure-object]
  (-> state
      (update :net h/seed-cell closure-env-id lexical-env)
      (update :net h/seed-cell closure-id closure-object)))

(defn ^:deprecated attach-closure-environment
  [state closure-id closure-env-id]
  (let [[prop-id network]
        ((obj/p:slot closure-value/closure-env-slot closure-env-id closure-id)
         (:net state))]
    [(-> state
         (assoc :net network)
         (h/add-props [prop-id]))
     prop-id]))

(defn declare-closure
  ([state inputs output body]
   (declare-closure state nil inputs output body))
  ([state name inputs output body]
   (let [{closure-output :output closure-body :body}
         (normalize-closure-output (hidden-return-symbol state) output body)
         proposed-id (h/node-id state :closure)
         reserved-id (when name
                       (env/reserved-binding-id (:net state) (:env state) name
                                                (:seed state)))
         closure-id (or reserved-id proposed-id)
         state' (if (and name (nil? reserved-id))
                  (-> state
                      (update :net h/ensure-cell closure-id)
                      (reserve-fixed-local name closure-id))
                  state)
         closure-object (closure-value/closure-object (:env state')
                                                      closure-body
                                                      inputs
                                                      closure-output
                                                      (:env state'))
         declaration-id (h/stable-node-id :compiler-2 :closure-declaration
                                          closure-id)
         graph-id (call-graph/graph-cell-id closure-id)
         compile* (cond
                    (fn? (:compiler state'))
                    (:compiler state')

                    :else
                    dispatch/default-compiler)
         captured-topology (env/captured-lexical-topology (:net state')
                                                          (:env state'))
         captured-cell-ids (env/captured-lexical-cell-ids (:net state')
                                                          (:env state'))
         callable (compiler-app/gur-closure compile* declaration-id
                                            closure-object body
                                            captured-topology
                                            captured-cell-ids)
         closure-binding (env/cell-binding closure-id)
         declared (-> state'
                      (update :net h/seed-cell declaration-id closure-object)
                      (update :net h/ensure-cell graph-id)
                      (update :net h/seed-cell closure-id callable))]
     [declared
      closure-binding])))

(defn- fresh-definition-id
  [state name source-id]
  (h/stable-node-id :compiler-2 :definition (:env state) name source-id))

(defn- definition-target-id
  [state name source-id]
  (or (env/reserved-binding-id (:net state) (:env state) name (:seed state))
      (when (:reuse-existing-bindings? state)
        (env/local-binding-id (:net state) (:env state) name))
      (fresh-definition-id state name source-id)))

(defn- copy-binding-value
  [state source-id target-id]
  (if (= source-id target-id)
    state
    (let [[prop-id network] ((stdlib-prop/id source-id target-id) (:net state))]
      (-> state
          (assoc :net network)
          (h/add-props [prop-id])))))

(defn define-binding
  [state name binding]
  (let [source-id (env/binding-id binding)]
    (when-not source-id
      (throw (ex-info "definition must declare an addressed binding"
                      {:name name :binding binding})))
    (let [reserved-id (env/reserved-binding-id (:net state) (:env state) name
                                                (:seed state))
          reused-id (when (:reuse-existing-bindings? state)
                      (env/local-binding-id (:net state) (:env state) name))
          target-id (definition-target-id state name source-id)
          target-binding (if (env/compound-binding? binding)
                           (env/compound-binding target-id)
                           (env/cell-binding target-id))
          state' (-> state
                     (update :net h/ensure-cell target-id)
                     (copy-binding-value source-id target-id))
          declared (if (or reserved-id reused-id)
                     state'
                     (declare-fixed-local state' name target-id))
          consumed (update declared :net
                           env/consume-reserved-binding
                           (:env state) name target-id (:seed state))]
      [consumed target-binding])))

(defn- define-operator-binding
  [state name operator result-binding]
  (let [source-id (env/binding-id result-binding)
        reserved-id (env/reserved-binding-id (:net state) (:env state) name
                                              (:seed state))
        target-id (or reserved-id source-id)
        declared (if reserved-id
                   state
                   (reserve-fixed-local state name target-id))
        seeded (-> declared
                   (update :net h/seed-cell target-id operator)
                   (update :net env/retain-compiler-declaration
                           target-id
                           operator))
        consumed (update seeded :net env/consume-reserved-binding
                         (:env state) name target-id (:seed state))]
    [consumed (env/cell-binding target-id)]))

(defn lower-let
  [expr]
  (let [bindings (ast/bindings expr)
        names (mapv first bindings)
        binding-forms
        (mapv (fn [[name value-expr]]
                (ast/app (ast/sym '->) value-expr (ast/sym name)))
              bindings)]
    (ast/let-cell names
                  (apply ast/sequence*
                         (concat binding-forms [(ast/body expr)])))))

(defn- declare-constraint-environment
  [state lexical-env name inputs arg-ids]
  (let [[scoped _env-id]
        (declare-child-environment (assoc state :env lexical-env)
                                   [:constraint name :env]
                                   inputs)]
    (reduce (fn [declared [sym id]]
              (declare-fixed-local declared sym id))
            scoped
            (map vector inputs arg-ids))))

(defn- constraint-argument-ids
  [name inputs arg-bindings]
  (let [arg-ids (mapv env/binding-id arg-bindings)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "constraint arguments must compile to cells"
                      {:constraint name :args arg-bindings})))
    (when-not (= (count inputs) (count arg-ids))
      (throw (ex-info "constraint application has wrong arity"
                      {:constraint name
                       :inputs inputs
                       :arg-count (count arg-ids)})))
    arg-ids))

(defn- constraint-result
  [calling-state body-state arg-ids body-binding]
  [(assoc body-state :env (:env calling-state))
   (env/cell-binding (or (peek arg-ids)
                         (:binding/id body-binding)))])

(defn- constraint-operator
  [compile* name lexical-env inputs body]
  (operator-value/operator-closure
   {:name name
    :direct-compiler
    (fn [compile-k state operand-forms _out-id k]
      (cps/compile-args
       compile-k state operand-forms
       (fn [state' arg-bindings]
         (let [arg-ids (constraint-argument-ids name inputs arg-bindings)
               body-state (h/child
                           (declare-constraint-environment state'
                                                           lexical-env
                                                           name
                                                           inputs
                                                           arg-ids)
                           [:constraint name])]
           (cps/call
            compile-k body-state body
            (fn [state'' body-binding]
              (let [[state''' result]
                    (constraint-result state' state'' arg-ids body-binding)]
                (cps/continue k state''' result))))))))
    :direct-installer
    (fn [state operand-forms _out-id]
      (let [[state' arg-bindings] (common/compile-args compile* state operand-forms)
            arg-ids (constraint-argument-ids name inputs arg-bindings)]
        (let [body-state (declare-constraint-environment state'
                                                         lexical-env
                                                         name
                                                         inputs
                                                         arg-ids)
              [state'' body-binding] (compile*
                                      (h/child body-state [:constraint name])
                                      body)]
          (constraint-result state' state'' arg-ids body-binding))))}))

(defn declare-constraint
  [compile* state expr]
  (let [operator (constraint-operator compile*
                                      (ast/name expr)
                                      (:env state)
                                      (ast/inputs expr)
                                      (ast/body expr))
        [state' result-binding] (h/new-cell state
                                            [:def-constraint (ast/name expr)]
                                            operator)]
    (define-operator-binding state' (ast/name expr) operator result-binding)))
