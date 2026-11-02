(ns propagators.compiler-2.session.operators
  "Compiler bindings declare pure activation or deferred dependency work."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.operators.environment :as requests]
            [propagators.network :as net]))

(defprotocol Operator
  (as-propagator-operator [operator binding-name hooks]))

(deftype PrimOp [constructor])
(defprotocol EffectInterpreter
  (interpret-effect! [interpreter owner context input emit!]))

(deftype AsyncOp [constructor interpreter])

(defn cell-output
  "Select a supported output-cell update in the current propagation round."
  [update]
  {::destination :cell ::output update})

(defn effect-output
  "Select an input for the injected interpreter, delivered only after commit."
  [input]
  {::destination :effect ::output input})

(defn next-round-input [update]
  {::route :next-round ::input update})

(defn dependency-input [dependency-id input]
  (when-not (keyword? dependency-id)
    (throw (ex-info "Dependency input requires a keyword identity" {})))
  {::route :dependency ::dependency dependency-id ::input input})

(defn effect-route [input]
  (let [route (::route input)]
    (when-not (and (map? input) (#{:next-round :dependency} route)
                   (contains? input ::input)
                   (or (= route :next-round) (keyword? (::dependency input))))
      (throw (ex-info "Interpreter must select a next-round or dependency input" {})))
    route))

(defn effect-input-value [input] (::input input))
(defn effect-dependency [input] (::dependency input))

(defprotocol FixedBinding
  (binding-implementation [binding]))

(deftype FrozenPrimitive [artifact implementation]
  Object
  (equals [_ other]
    (and (instance? FrozenPrimitive other)
         (= artifact (.-artifact ^FrozenPrimitive other))))
  (hashCode [_] (hash artifact)))

(extend-type FrozenPrimitive
  FixedBinding
  (binding-implementation [binding] (.-implementation ^FrozenPrimitive binding)))

(defn frozen-primitive
  "An immutable trusted module artifact; equality is its content-addressed identity."
  [artifact implementation]
  (FrozenPrimitive. artifact implementation))

(defn prim-op [constructor]
  (when-not (fn? constructor)
    (throw (ex-info "Primitive constructor must be a function" {})))
  (PrimOp. constructor))

(defn async-op
  ;; The single-argument form preserves the existing dependency callback adapter.
  ;; The async operator builder requires explicit interpreter injection.
  ([constructor]
   (when-not (fn? constructor)
     (throw (ex-info "Async constructor must be a function" {})))
   (AsyncOp. constructor nil))
  ([effect-interpreter constructor]
   (when-not (and (satisfies? EffectInterpreter effect-interpreter) (fn? constructor))
     (throw (ex-info "Async operator requires an injected effect interpreter and constructor" {})))
   (AsyncOp. constructor effect-interpreter)))

(defn operator-maker
  "declare-call installs a call and returns its output cell; it never invokes f."
  [declare-call]
  (fn [f] (fn [cells] (declare-call f cells))))

(defn primitive-operator-maker [declare-primitive]
  (operator-maker declare-primitive))

(declare install-call! async-operator)

(defn async-primitive-maker
  "Build f -> cells -> output-cell using one injected effect interpreter.
  f is pure and selects cell-output or effect-output. Effect delivery is deferred."
  [effect-interpreter]
  (when-not (satisfies? EffectInterpreter effect-interpreter)
    (throw (ex-info "Async operator builder requires an effect interpreter" {})))
  (operator-maker
   (fn [f cells]
     (when-not (fn? f)
       (throw (ex-info "Async operator behavior must be a pure function" {})))
     (let [{:keys [binding-name hooks]} (::builder (meta cells))]
       (install-call! (async-operator binding-name f effect-interpreter hooks) cells)))))

(def callback-kind ::callback)

(defn callback-request? [request]
  (= callback-kind (:boundary/kind request)))

(defn argument-state [network input-ids]
  {:values (mapv #(net/network-cell-strongest network %) input-ids)
   :contents (mapv #(net/network-cell-content network %) input-ids)
   :bases (basis/primitive-input-values network input-ids)})

(defn eligible?
  "A retained outbox request is not authority to copy an obsolete input."
  [network request]
  (let [{:keys [input-ids input-contents]} (:boundary/payload request)
        current (argument-state network input-ids)]
    (and (= input-contents (:contents current))
         (not-any? value/unusable? (:bases current)))))

(defn- callback-target [binding-name dependency-id dependency-ids]
  ;; Preserve the existing declared-dependency adapter. New behaviors use the
  ;; injected async operator builder; callbacks cannot bypass its interpreter.
  (when-not (keyword? dependency-id)
    (throw (ex-info "Inject an effect interpreter for async operator behavior"
                    {:binding binding-name})))
  (when-not (contains? dependency-ids dependency-id)
    (throw (ex-info "Async binding references an undeclared dependency"
                    {:binding binding-name :dependency dependency-id})))
  {:dependency dependency-id})

(defn- callback-operator [binding-name target {:keys [run-id outbox-id]}]
  (when-not (and run-id outbox-id)
    (throw (ex-info "Async binding requires run and outbox identities" {:binding binding-name})))
  (operator-value/propagator-operator
   {:name binding-name
    :activate
    (fn [network input-ids output-ids _context]
      (let [{:keys [bases contents]} (argument-state network input-ids)
            output-id (first output-ids)]
        (if (some value/unusable? bases)
          (basis/primitive-reply-messages network input-ids output-id value/nothing)
          (into (vec (basis/primitive-reply-messages
                       network input-ids output-id value/nothing))
                [(requests/effect-request-message
                  outbox-id
                  [callback-kind run-id binding-name output-id contents]
                  callback-kind
                  (merge target {:arguments bases
                                 :input-ids (vec input-ids) :input-contents contents
                                 :run-id run-id})
                  output-id)]))))}))

(defn- async-operator [binding-name f interpreter {:keys [run-id outbox-id]}]
  (when-not (and binding-name run-id outbox-id)
    (throw (ex-info "Async operator requires a canonical compiler binding context" {})))
  (operator-value/propagator-operator
   {:name binding-name
    :activate
    (fn [network input-ids output-ids _context]
      (let [{:keys [bases contents]} (argument-state network input-ids)
            output-id (first output-ids)]
        (if (some value/unusable? bases)
          (basis/primitive-reply-messages network input-ids output-id value/nothing)
          (let [output (apply f bases)
                destination (::destination output)]
            (when-not (and (map? output) (#{:cell :effect} destination)
                           (= #{::destination ::output} (set (keys output))))
              (throw (ex-info "Async operator must select cell-output or effect-output"
                              {:binding binding-name})))
            (case destination
              :cell (basis/primitive-reply-messages network input-ids output-id (::output output))
              :effect
              (into (vec (basis/primitive-reply-messages network input-ids output-id value/nothing))
                    [(requests/effect-request-message
                      outbox-id [callback-kind run-id binding-name output-id contents]
                      callback-kind
                      {:interpreter interpreter :effect-input (::output output)
                       :arguments bases :input-ids (vec input-ids) :input-contents contents
                       :run-id run-id}
                      output-id)]))))))}))

(defn- install-call! [operator cells]
  (let [scope (::installation (meta cells))]
    (when-not scope
      (throw (ex-info "Operator cells require a canonical installation scope" {})))
    (let [{:keys [network output-id open? called?]} @scope]
      (when-not open? (throw (ex-info "Operator installation scope is closed" {})))
      (when called? (throw (ex-info "A primitive declares one call; compose calls in Lain" {})))
      (let [[installed props output] ((operator-value/operator-install operator) network cells output-id)]
        (reset! scope {:network installed :props props :output-id output :open? true :called? true})
        output))))

(defn- build-operator [binding-name constructor make lower hooks]
  (let [declare-call (make install-call!)
        callable (constructor (fn [behavior] (declare-call (lower behavior))))]
    (when-not (fn? callable)
      (throw (ex-info "Operator constructor must return the maker's cell function" {:binding binding-name})))
    (operator-value/operator-closure
     {:name binding-name
      :application-installer
      (fn [network input-ids output-id _context-id]
        ;; Mutable construction bookkeeping is invocation-local, never part of a
        ;; session snapshot or a callback's commit capability.
        (let [scope (atom {:network network :props [] :output-id output-id :open? true :called? false})
              cells (with-meta (vec input-ids)
                      {::installation scope ::builder {:binding-name binding-name :hooks hooks}})]
          (try
            (let [output (callable cells)
                  installed @scope]
              (when-not (and (:called? installed) (= output-id output (:output-id installed)))
                (throw (ex-info "Operator must return its caller-owned output cell" {:binding binding-name})))
              [(:network installed) (:props installed) output])
            (finally (swap! scope assoc :open? false)))))})))

(extend-type PrimOp
  Operator
  (as-propagator-operator [operator binding-name hooks]
    (let [constructor (.-constructor ^PrimOp operator)]
      (if-let [declare-call (:declare-primitive hooks)]
        ((:bind hooks) binding-name
         (constructor (primitive-operator-maker declare-call)))
        (build-operator binding-name constructor primitive-operator-maker basis/primitive-operator hooks)))))

(extend-type AsyncOp
  Operator
  (as-propagator-operator [operator binding-name hooks]
    (let [constructor (.-constructor ^AsyncOp operator)
          interpreter (.-interpreter ^AsyncOp operator)]
      (cond
        interpreter
        (build-operator binding-name constructor
                        (fn [_] (async-primitive-maker interpreter)) identity hooks)

        (:declare-async hooks)
        ((:bind hooks) binding-name
         (constructor (operator-maker (:declare-async hooks))))

        :else
        (build-operator binding-name constructor operator-maker
                        (fn [behavior]
                          (callback-operator binding-name
                            (callback-target binding-name behavior (:dependency-ids hooks)) hooks))
                        hooks)))))

(defn compiler-binding [binding-name declaration context]
  (let [implementation (if (satisfies? FixedBinding declaration)
                         (binding-implementation declaration)
                         declaration)]
    (if (satisfies? Operator implementation)
      (as-propagator-operator implementation binding-name
                              (assoc context :outbox-id (runtime-ids/boundary-outbox-id)))
      implementation)))
