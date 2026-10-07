(ns propagators.compiler-2.compiler.declarations
  "Pure compiler-2 declarations, separate from traversal strategy."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.application :as compiler-app]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.context :as context]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.compiler.dispatch :as dispatch]
            [propagators.compiler-2.compiler.rest-closure :as rest-closure]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.operators.call-graph :as call-graph]
            [propagators.compiler-common.core :as common]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.gur :as gur]
            [propagators.core :as core]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.network-builder :as nb]
            [propagators.stdlib.prop :as stdlib-prop]))

(def declaration-link-key :compiler-2/operator-declaration)

(defn declare-operator-description
  "Retain the known compile-time declaration beside its callable cell.
   The projection remains visible in the graph; runtime application uses the cell."
  [state binding candidate]
  (if (operator-value/operator-closure? candidate)
    (let [id (env/binding-id binding)
          declaration-id (h/stable-node-id declaration-link-key id)
          declaration (operator-value/operator-declaration candidate)
          prepared (-> (:net state)
                       (nb/ensure-cell declaration-id)
                       (nb/seed-cell declaration-id declaration))
          [prop-id network]
          (((prop/concrete-primitive-propagator
             [:compiler-2/operator-declaration id]
             operator-value/operator-declaration)
            id declaration-id) prepared)
          [_ named]
          (core/eval-activation-result
           (gur/bind-name declaration-link-key id declaration-id) network)]
      [(-> state (assoc :net named) (h/add-props [prop-id]))
       (assoc binding declaration-link-key declaration-id)])
    [state binding]))

(defn known-operator-declaration [state binding]
  (if-let [id (or (get binding declaration-link-key)
                  (get-in (net/network-dict-entry (:net state) gur/name-bindings-key)
                          [declaration-link-key (env/binding-id binding)]))]
    (net/network-cell-strongest (:net state) id)
    nil))

(defn declare-definition-receipt [state source target]
  (let [id (h/node-id state :definition-receipt)]
    (h/new-cell state :definition-receipt
                {:declaration/kind :binding :declaration/id id
                 :binding/source (env/binding-id source)
                 :binding/target (env/binding-id target)})))

(defn declare-definition
  [state name target source]
  (let [source-id (env/binding-id source)
        target-id (env/binding-id target)
        receipt-id (h/node-id state :definition-receipt)
        effects [(gur/declare-cell receipt-id)
                 (gur/declare-prop
                  (gur/stable-node-id [:compiler-2/definition receipt-id source-id target-id])
                  [:compiler-2/definition receipt-id]
                  [source-id] [target-id]
                  (fn [_ _ network]
                    (let [content (net/network-cell-content network source-id)]
                      (if (value/unusable? content)
                        []
                        [(message target-id content)]))))]
        result {:effects effects
                :messages [(message receipt-id
                                    {:declaration/kind :binding
                                     :declaration/id receipt-id
                                     :binding/source source-id
                                     :binding/target target-id})]}
        [_ network] (core/eval-activation-result result (:net state))
        network (env/consume-reserved-binding network (:env state) name target-id (:seed state))
        declared (-> state (assoc :net network) (h/add-props [(-> effects second :id)]))
        description (known-operator-declaration state source)
        [declared _]
        (if description
          (declare-operator-description declared target description)
          [declared target])]
    [declared (env/cell-binding receipt-id)]))

(defn- application-operator-label
  [operator-ast]
  (cond
    (= :symbol (ast/type operator-ast))
    (str (ast/name operator-ast))

    :else
    (str (ast/type operator-ast))))

(defn- install-call-publisher
  [state app-id operator-id operator-ast]
  (if-let [caller-id (:application/caller state)]
    (let [[prop-id network]
          ((call-graph/p:application-call
            caller-id app-id operator-id
            (application-operator-label operator-ast))
           (:net state))]
      (-> state
          (assoc :net network)
          (h/add-props [prop-id])))
    state))

(defn argument-cell-ids
  [arg-bindings]
  (let [arg-ids (mapv env/binding-id arg-bindings)]
    (if (every? ids/node-id? arg-ids)
      arg-ids
      (throw
       (ex-info "Application arguments must compile to cells"
                {:arguments arg-bindings})))))

(defn declare-application-context
  [state expression]
  (let [[result-state result-binding]
        (h/new-cell state :result)
        result-id (env/binding-id result-binding)
        application-id
        (h/stable-node-id (:seed result-state)
                          (:path result-state)
                          :application
                          result-id)
        operator-ast (ast/ast (ast/operator expression))
        [context-state context-binding]
        (h/new-cell result-state
                    :context
                    (context/context-value (:env result-state)
                                           application-id
                                           operator-ast))]
    [(assoc context-state
            :context-id (env/binding-id context-binding)
            :application/app-id application-id
            :application/operator-ast operator-ast)
     result-id]))

(defn declare-operator-cell
  [state operator-binding argument-ids fallback-result-id]
  (if-let [operator-id (env/binding-id operator-binding)]
    {:state state
     :operator-id operator-id
     :result-id (if-let [declaration (known-operator-declaration state operator-binding)]
                  (h/output-id declaration argument-ids fallback-result-id)
                  fallback-result-id)}
    (if (or (operator-value/operator-closure? operator-binding)
            (fn? operator-binding))
      (let [[prepared operator-cell]
            (common/install-operator-object state operator-binding)]
        {:state prepared
         :operator-id (env/binding-id operator-cell)
         :result-id (h/output-id operator-binding
                                 argument-ids
                                 fallback-result-id)})
      (throw
       (ex-info "Application operator is not callable"
                {:operator operator-binding})))))

(defn declare-application-topology
  [state operator-binding argument-bindings fallback-result-id]
  (let [argument-ids (argument-cell-ids argument-bindings)
        {:keys [state operator-id result-id]}
        (declare-operator-cell state operator-binding
                               argument-ids fallback-result-id)
        prepared (assoc state :application/arg-ids argument-ids)
        [installed result-binding]
        (compiler-app/install-application prepared operator-id
                                          argument-ids result-id)
        published
        (install-call-publisher installed
                                (:application/app-id installed)
                                operator-id
                                (:application/operator-ast installed))]
    [published result-binding]))

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

(defn- install-env-topology
  [state installer]
  (let [[prop-ids network] (installer (:net state))]
    (-> state
        (assoc :net network)
        (h/add-props prop-ids))))

(defn declare-child-environment
  [state role local-names]
  (let [child-id (h/node-id state role)
        state' (update state :net nb/ensure-cell child-id)]
    [(-> state'
         (install-env-topology
          (env/p:scope-frame (:env state) child-id local-names))
         (assoc :env child-id))
     child-id]))

(defn declare-local
  [state sym binding-id]
  (install-env-topology state
                        (env/p:declare-canonical-local
                         sym (:env state) binding-id)))

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
                        (update :net nb/ensure-cell binding-id)
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

(defn- declare-fixed-closure
  ([state inputs output body]
   (declare-fixed-closure state nil inputs output body))
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
                      (update :net nb/ensure-cell closure-id)
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
         callable (compiler-app/closure-callable
                   compile*
                   [:compiler-2/closure declaration-id]
                   declaration-id
                   (:env state')
                   closure-object)
         callable (assoc callable
                         :compiler-2/input-description (vec inputs)
                         :compiler-2/output-interface :body-return)
         closure-binding (env/cell-binding closure-id)
         declared (-> state'
                      (update :net h/seed-cell declaration-id closure-object)
                      (update :net nb/ensure-cell graph-id)
                      (update :net h/seed-cell closure-id callable))]
     [declared
      closure-binding])))

(def declare-closure (rest-closure/declaration declare-fixed-closure))

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
