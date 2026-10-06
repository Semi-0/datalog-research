(ns experiments.functional-network
  "Experiment: expression-returned cell lists define a network's output ports."
  (:require [clojure.walk :as walk]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.compiler.declarations :as declarations]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-common.cps :as cps]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def output-scope :experiment/network-outputs)

(declare apply-list-effects)

(defn- list-shape [network list-id]
  (let [candidate (net/network-cell-strongest network list-id)
        head (obj/existing-slot-cell-id network :car list-id)
        tail (obj/existing-slot-cell-id network :cdr list-id)]
    (cond
      (value/contradiction? candidate) :wait
      (and head tail) :cons
      (or head tail) :wait
      (value/nothing? candidate) :wait
      (= basis/list-empty-marker candidate) :empty
      (obj/accessor-network? candidate) :wait
      :else :scalar)))

(defn- list-readiness-effects [key list-id condition-id]
  [(gur/declare-cell condition-id)
   (gur/declare-prop
    (gur/stable-node-id [key :readiness])
    [:experiment/list-readiness key] [list-id] [condition-id]
    (fn [_ _ network]
      (let [shape (list-shape network list-id)]
        (if (= :wait shape)
          []
          [(message condition-id shape)]))))])

(defn- apply-list-body [operator-id context-id prefix seen]
  (fn [context [list-id] result-id]
    (let [network (:network context)
          head (obj/existing-slot-cell-id network :car list-id)
          tail (obj/existing-slot-cell-id network :cdr list-id)]
      (when (contains? seen list-id)
        (throw (ex-info "Cyclic input interfaces are unsupported" {:list-id list-id})))
      (case (list-shape network list-id)
        :empty
        {:effects [(gur/apply-closure-effect operator-id
                                            (into [context-id] prefix)
                                            result-id)]}
        :cons
        {:effects (apply-list-effects operator-id context-id
                                      (conj prefix head) tail result-id
                                      (conj seen list-id))}
        :wait {:effects []}
        :scalar
        (throw (ex-info "apply requires a topology-backed linked list"
                        {:list-id list-id}))))))

(defn- apply-list-effects
  ([operator-id context-id prefix list-id result-id]
   (apply-list-effects operator-id context-id prefix list-id result-id #{}))
  ([operator-id context-id prefix list-id result-id seen]
   (let [key [:experiment/apply-list operator-id context-id prefix list-id result-id]
         closure-id (gur/stable-node-id [key :closure])
         condition-id (gur/stable-node-id [key :ready])]
     (into (list-readiness-effects key list-id condition-id)
           [(gur/declare-cell closure-id)
            (gur/when-effect
             key condition-id
             (fn []
               {:messages [(message closure-id
                                    (gur/recursive-closure
                                     key (apply-list-body operator-id context-id prefix seen)))]
                :effects [(gur/apply-closure-effect closure-id [list-id] result-id)]}))]))))

(def apply-operator
  (operator/operator-closure
   {:name 'apply
    :compiler-activate
    (fn [_ network context-id arguments result-id]
      (if (= 2 (count arguments))
        {:effects (apply-list-effects (first arguments) context-id []
                                      (second arguments) result-id)}
        (throw (ex-info "apply expects a network and an argument-cell list"
                        {:arguments arguments}))))}))

(defn linked-list-application-effects [operator-id context-id list-id result-id]
  (apply-list-effects operator-id context-id [] list-id result-id))

(defn- copy-effect [scope source target]
  (gur/declare-prop
   (gur/stable-node-id [scope source target])
   [:experiment/boundary scope source target]
   [source] [target]
   (prop/concrete-propagator
    (fn [_ _ network]
      [(message
        target (net/network-cell-content network source))]))))

(declare scan-output-effect)

(defn- output-boundary-effects [routes application-id position head]
  (let [port-id (gur/stable-node-id [application-id :output-port position])]
    (into [(gur/declare-cell port-id)
           (gur/bind-name output-scope [application-id position] port-id)
           (copy-effect [application-id :outbound position] head port-id)]
          (if-let [outer (get routes head)]
            [(copy-effect [application-id :caller position] port-id outer)]
            []))))

(defn- output-list-body [routes application-id position seen]
  (fn [context [list-id] _]
    (let [network (:network context)]
      (when (contains? seen list-id)
        (throw (ex-info "Cyclic output interfaces are unsupported" {:list-id list-id})))
      (case (list-shape network list-id)
        :cons
        (let [head (obj/existing-slot-cell-id network :car list-id)
              tail (obj/existing-slot-cell-id network :cdr list-id)]
          {:effects
           (into (output-boundary-effects routes application-id position head)
                 (scan-output-effect routes application-id (inc position) tail
                                     (conj seen list-id)))})
        :empty {:effects []}
        :wait {:effects []}
        :scalar
        (if (zero? position)
          {:effects []}
          (throw (ex-info "Output interface has a non-list tail"
                          {:list-id list-id :position position})))))))

(defn- scan-output-effect
  ([routes application-id position list-id]
   (scan-output-effect routes application-id position list-id #{}))
  ([routes application-id position list-id seen]
   ;; Availability waits for list topology, not for the values of its members.
   (let [closure-id (gur/stable-node-id [application-id :output-scanner position])
         out-id (gur/stable-node-id [application-id :output-scan-result position])
         condition-id (gur/stable-node-id [application-id :output-ready position])
         key [application-id :outputs position]]
     (into (list-readiness-effects key list-id condition-id)
           [(gur/declare-cell closure-id)
            (gur/declare-cell out-id)
            ;; Each recursive step has a distinct position and semantic identity.
            (gur/when-effect
             key condition-id
             (fn []
               {:messages
                [(message
                  closure-id
                  (gur/recursive-closure
                   [:experiment/outputs position]
                   (output-list-body routes application-id position seen)))]
                :effects [(gur/apply-closure-effect closure-id [list-id] out-id)]}))]))))

(defn register-returned-outputs [declared routes application-id result-id]
  (update declared :effects into
          (scan-output-effect routes application-id 0 result-id)))

(defn- inbound-routes [network]
  (reduce-kv
   (fn [routes id {:keys [inputs outputs]}]
     (let [entry (get (net/net-env network) id)
           name (when (prop/prop? entry) (prop/prop-name entry))]
       (if (and (vector? name)
                (= :compiler-2/application (first name))
                (= :inbound (nth name 2 nil)))
         (if (and (= 1 (count inputs)) (= 1 (count outputs)))
           (assoc routes (first outputs) (first inputs))
           (throw (ex-info "Inbound boundary must connect two cells" {:boundary id})))
         routes)))
   {} (net/net-graph network)))

(defn- definition-route-effects [routes application-id target]
  (loop [source target seen #{} effects []]
    (if-let [outer (get routes source)]
      (if (contains? seen source)
        (throw (ex-info "Cyclic invocation input routing" {:target target}))
        (recur outer (conj seen source)
               (conj effects (copy-effect [application-id :defined-input source outer]
                                          source outer))))
      effects)))

(defn- defined-input-effects [effects routes application-id]
  ;; A definition targeting an input cell is a visible connection back to its
  ;; caller. Inspect declaration names, never the value stored in that cell.
  (->> effects
       (filter #(= :network/declare-propagator (:op %)))
       (mapcat (fn [{:keys [name outputs]}]
                 (if (and (vector? name)
                          (= :experiment/boundary (first name))
                          (vector? (second name))
                          (= :experiment/definition (first (second name))))
                   (definition-route-effects routes application-id (first outputs))
                   [])))
       vec))

(defn compile-and-register-body
  [body-declaration routes context invocation result-id]
  (let [declared (body-declaration context invocation result-id)
        application-id (:app-key context)
        routes (merge (inbound-routes (:network context)) routes)]
    (-> declared
        (update :effects into
                (defined-input-effects (:effects declared) routes application-id))
        (register-returned-outputs routes application-id result-id))))

(defn- expression-application [callable]
  (let [original (:gur.flat/body callable)]
    (assoc callable :gur.flat/body
           (fn [context invocation result-id]
             (let [app-id (:app-key context)
                   arguments (subvec (vec invocation) 1)
                   locals (mapv #(gur/stable-node-id [app-id :local-input %])
                                (range (count arguments)))
                   routes (zipmap locals arguments)]
               (compile-and-register-body original routes context invocation result-id))))))

(defn network-receipt [callable declaration-id input-description]
  (assoc callable
         :experiment/declaration-id declaration-id
         :experiment/input-description input-description
         :experiment/output-interface :body-return))

(defn- compile-network [_ state expression continuation]
  (let [[declared binding]
        (declarations/declare-closure state (ast/inputs expression)
                                      (ast/output expression)
                                      (ast/body expression))
        id (env/binding-id binding)
        callable (net/network-cell-strongest (:net declared) id)]
    (cps/continue continuation
                  (update declared :net nb/seed-cell id
                          (network-receipt (expression-application callable)
                                           id (ast/inputs expression)))
                  binding)))

(defn binding-declaration [definition-id source-id target-id receipt-id]
  {:effects [(gur/declare-cell receipt-id)
             (copy-effect [:experiment/definition definition-id] source-id target-id)]
   :messages [(message receipt-id
                       {:declaration/kind :binding
                        :declaration/id definition-id
                        :binding/source source-id
                        :binding/target target-id})]})

(defn- install-definition [state name target-binding source-binding continuation]
  (let [source-id (env/binding-id source-binding)
        target-id (env/binding-id target-binding)
        receipt-id (basis/node-id state :definition-receipt)]
    (when-not (and source-id target-id)
      (throw (ex-info "Definition operands must compile to cells" {:name name})))
    (let [result (binding-declaration receipt-id source-id target-id receipt-id)
          [_ network] (core/eval-activation-result result (:net state))
          prop-ids (mapv :id (filter #(= :network/declare-propagator (:op %))
                                    (:effects result)))]
      (cps/continue continuation
                    (-> state (assoc :net network) (basis/add-props prop-ids))
                    (env/cell-binding receipt-id)))))

(defn- compile-definition [compile-k state expression continuation]
  (let [name (ast/name expression)
        path (:path state)
        body (ast/body expression)]
    (cps/call compile-k (basis/child state :definition-target) (ast/sym name)
              (fn [target-state target-binding]
                (if body
                  (cps/call compile-k (basis/child (assoc target-state :path path)
                                                    :definition-value)
                            body
                            (fn [compiled source-binding]
                              (install-definition (assoc compiled :path path) name
                                                  target-binding source-binding
                                                  continuation)))
                  (let [[declared source-binding]
                        (basis/new-cell (assoc target-state :path path) :definition-value)]
                    (install-definition declared name target-binding source-binding
                                        continuation)))))))

(def expression-rules
  (cps/compose-rules
    (cps/on #(= :network (ast/type %)) compile-network)
    (cps/on #(= :def (ast/type %)) compile-definition)
    compiler/compiler-dispatch))

(defn- continue-with-cell [continuation state binding]
  (if (env/binding-id binding)
    (cps/continue continuation state binding)
    (let [[declared result] (basis/new-cell state :expression-value binding)]
      (cps/continue continuation declared result))))

(defn- compile-cell-expression [compile-k state expression continuation]
  (expression-rules compile-k state expression
                    (partial continue-with-cell continuation)))

(def expression-compiler
  (cps/make-compiler compile-cell-expression))

(defn parse-form [form]
  (parser/parse-form
   (walk/postwalk
    (fn [form]
      (if (seq? form)
        (case (first form)
          define (cons 'def (rest form))
          list (if (empty? (rest form)) basis/list-empty-marker form)
          network (let [[_ parameters & body] form]
                    (if (and (sequential? parameters)
                             (every? symbol? parameters)
                             (seq body))
                      (list* 'cell-expr (vec parameters) body)
                      (throw (ex-info "Computed network input descriptions are unsupported"
                                      {:capability :computed-input-description
                                       :form form}))))
          form)
        form))
    form)))

(defn parse-source [source]
  (parse-form (parser/read-form source)))

(defn outputs [network]
  (get (net/network-dict-entry network gur/name-bindings-key) output-scope {}))
