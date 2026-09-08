(ns propagators.compiler-2.runtime.application
  "Application propagator for compiler-2 network closures.

  Closure cells are data. This namespace owns runtime application: bind
  arguments into an activation-local environment, compile the
  body into a transient activation network, run it, and emit only the declared
  output diff back to the outer network.
  "
  (:require [propagators.boundary :as boundary]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.application-value :as application-value]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.compiler.dispatch :as dispatch]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.runtime.activation :as activation]
            [propagators.compiler-2.runtime.sub-environment :as sub-environment]
            [propagators.compiler-2.runtime.application-output :as output]
            [propagators.compiler-2.runtime.application-layers :as layers]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as stdlib-prop]))

(def apply-closure-props-key :compiler-2/apply-closure-props)
(def apply-application-props-key :compiler-2/apply-application-props)
(def execute-sub-env-props-key sub-environment/execute-sub-env-props-key)
(def application-extra-output-ids-key :compiler-2/application-extra-output-ids)

(defn- output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else []))

(defn declare-closure-environment
  "Declare one live closure frame and all of its addressed locals."
  [network lexical-env-id frame-id inputs output-targets input-ids]
  (let [declarations (concat (keep (fn [[sym id]] (if sym [sym id] nil))
                                   output-targets)
                             (map vector inputs input-ids))
        [sub-props network']
        ((env/p:scope-frame lexical-env-id frame-id (map first declarations))
         (h/ensure-cell network frame-id))]
    (reduce
     (fn [[props n] [sym id]]
       (let [[ids n'] ((env/p:declare-canonical-local sym frame-id id) n)]
         [(into props ids) n']))
     [(vec sub-props) network']
     declarations)))

(defn ^:deprecated closure-body-env
  [lexical-env inputs output-targets input-ids]
  (let [base-env (reduce (fn [scoped-env [sym id]]
                           (if sym
                             (env/bind-local scoped-env sym (env/cell-binding id))
                             scoped-env))
                         (env/sub-env lexical-env)
                         output-targets)]
    (reduce (fn [scoped-env [sym id]]
              (env/bind-local scoped-env sym (env/cell-binding id)))
            base-env
            (map vector inputs input-ids))))

(defn- compile-body
  [compile* body env-id state]
  (compile* (assoc state :env env-id :compiler compile*) body))

(defn prepare-closure-frame
  "Compile a closure body against an already-bound frame environment.

  Returns declaration data only; callers choose transient execution or an
  outer-network topology diff."
  ([network closure-info frame-env-id compile-state]
   (prepare-closure-frame dispatch/default-compiler
                          network closure-info frame-env-id compile-state))
  ([compile* network closure-info frame-env-id compile-state]
   (let [[state result]
         (compile-body compile*
                       (closure-value/closure-body closure-info)
                       frame-env-id
                       (merge {:net network
                               :env frame-env-id
                               :seed [:compiler-2/apply-closure
                                      (closure-value/closure-scope closure-info)]
                               :path []
                               :props []
                               :applications []
                               :compiler compile*}
                              compile-state))]
     {:state state
      :net (:net state)
      :props (:props state)
      :result result
      :result-id (env/binding-id result)})))

(defn- install-output-adapter
  [n result-id out-inner]
  (if (and result-id (not= result-id out-inner))
    (let [[prop-id n'] ((stdlib-prop/id result-id out-inner) n)]
      [n' [prop-id]])
    [n []]))

(defn- install-output-adapters
  [n result-id output-inners]
  (if (= 1 (count output-inners))
    (install-output-adapter n result-id (first output-inners))
    [n []]))

(defn- application-extra-output-ids
  [network]
  (vec (get (net/net-dict-or-empty network)
            application-extra-output-ids-key
            #{})))

(defn- application-external-output-ids
  [network output-ids]
  (vec (distinct (concat output-ids
                         (application-extra-output-ids network)))))

(defn- output-inners-ready?
  [network output-inners]
  (and (seq output-inners)
       (every? (fn [out-inner]
                 (not (value/unusable?
                       (output/externalized-cell-value network out-inner))))
               output-inners)))

(defn- run-closure-body
  [compile* network closure-info arg-ids output-targets]
  (let [inputs (closure-value/closure-inputs closure-info)
        lexical-env (closure-value/closure-env closure-info)
        body (closure-value/closure-body closure-info)
        output-ids (mapv second output-targets)
        external-output-ids (application-external-output-ids network output-ids)]
    (if (or (value/unusable? lexical-env)
            (value/unusable? body)
            (not= (count inputs) (count arg-ids)))
      network
      (-> (reduce h/ensure-cell network external-output-ids)
          (boundary/create-boundary-outputs external-output-ids)
          (boundary/create-boundary-inputs arg-ids)
          (#(let [inner-inputs (mapv (partial net/lookup-inner-in %) arg-ids)
                  output-inners (mapv (partial net/lookup-inner-out %) output-ids)
                  frame-id (h/stable-node-id :compiler-2/transient-frame
                                             lexical-env arg-ids output-ids)
                  lexical-env-value (h/strongest-or-nothing network lexical-env)
                  with-lexical-env (h/seed-cell % lexical-env lexical-env-value)
                  [env-props activation-net]
                  (declare-closure-environment
                   with-lexical-env
                   lexical-env
                   frame-id
                   inputs
                   (mapv (fn [[sym _id] inner-id] [sym inner-id])
                         output-targets
                         output-inners)
                   inner-inputs)
                  prepared (prepare-closure-frame
                            compile*
                            activation-net
                            closure-info
                            frame-id
                            {:seed [:compiler-2/apply-closure
                                    (closure-value/closure-scope closure-info)
                                    arg-ids
                                    output-ids]})
                  result-id (:result-id prepared)
                  body-net (activation/run-network (:net prepared)
                                                   inner-inputs
                                                   (into (vec env-props)
                                                         (:props prepared)))]
              (if (output-inners-ready? body-net output-inners)
                body-net
                (let [[activation-net adapter-props]
                      (install-output-adapters body-net
                                               result-id
                                               output-inners)]
                  (activation/run-network activation-net
                                          inner-inputs
                                          adapter-props)))))))))

(defn closure-call-plan
  [closure-info arg-ids out-id]
  (let [input-count (count (closure-value/closure-inputs closure-info))
        output-syms (output-symbols (closure-value/closure-output closure-info))
        output-count (count output-syms)
        arg-ids (vec arg-ids)
        implicit-return? (closure-value/implicit-return-output?
                          (closure-value/closure-output closure-info))]
    (cond
      (and implicit-return? (= (count arg-ids) input-count))
      {:input-ids arg-ids
       :targets [[(first output-syms) out-id]]}

      (pos? output-count)
      (if (= (count arg-ids) (+ input-count output-count))
        {:input-ids (subvec arg-ids 0 input-count)
         :targets (mapv vector
                        output-syms
                        (subvec arg-ids input-count))}
        nil)

      (= (count arg-ids) input-count)
      {:input-ids arg-ids
       :targets [[nil out-id]]}

      :else nil)))

(defn closure-application-messages-with
  [compile* closure-id _args-id scheduled-arg-ids out-id network]
  (let [closure-cv (h/strongest-or-nothing network closure-id)
        closure-info (layers/unwrap-operator closure-cv)
        arg-ids (vec scheduled-arg-ids)]
    (if (or (value/unusable? closure-cv)
            (not (closure-value/closure-info? closure-info)))
      []
      (let [{:keys [input-ids targets]} (closure-call-plan closure-info
                                                           arg-ids
                                                           out-id)
            input-values (mapv #(h/strongest-or-nothing network %) input-ids)]
        (if (or (nil? targets)
                (value/any-unusable-values? input-values))
          []
          (let [output-ids (mapv second targets)
                network* (reduce h/ensure-cell network output-ids)
                after-body (run-closure-body compile*
                                             network*
                                             closure-info
                                             (vec input-ids)
                                             targets)]
            {:messages (output/externalized-output-messages after-body
                                                     network*
                                                     (application-external-output-ids
                                                      network*
                                                      output-ids))}))))))

(defn closure-application-messages
  [closure-id args-id scheduled-arg-ids out-id network]
  (closure-application-messages-with dispatch/default-compiler
                                     closure-id args-id scheduled-arg-ids
                                     out-id network))

(defn p:apply-closure-with
  "Apply a compiler-2 closure-info cell to argument cells and one output cell."
  [compile* closure-id args-id arg-ids out-id]
  (let [arg-ids (vec arg-ids)
        activate (fn [_inputs _outputs network]
                   (closure-application-messages-with compile*
                                                      closure-id
                                                      args-id
                                                      arg-ids
                                                      out-id
                                                      network))
        inputs (into [closure-id args-id] arg-ids)]
    (fn [network]
      (let [network* (reduce h/ensure-cell network (conj inputs out-id))
            [prop-id n] ((prop/construct-propagator :compiler-2/apply-closure
                                                    activate inputs [out-id])
                         network*)]
        [prop-id
         (net/update-net-dict-entry n
                                    apply-closure-props-key
                                    (fnil conj #{})
                                    prop-id)]))))

(defn p:apply-closure
  [closure-id args-id arg-ids out-id]
  (p:apply-closure-with dispatch/default-compiler
                        closure-id args-id arg-ids out-id))

(defn- primitive-application-messages
  [compile* operator context-id arg-ids out-id network]
  (let [compiler-activate (operator-value/operator-compiler-activate operator)]
    (if compiler-activate
      (compiler-activate compile* network context-id arg-ids out-id)
      (let [activate (h/application-activate operator)]
        (if activate
          (activate network context-id arg-ids out-id)
          [])))))

(defn application-messages-with
  [compile* application-id operator-id args-id scheduled-arg-ids context-id out-id
   network]
  (let [application-info (h/strongest-or-nothing network application-id)
        operator-answer (h/strongest-or-nothing network operator-id)
        operator (layers/unwrap-operator operator-answer)
        scope-arg-ids (if (closure-value/closure-info? operator)
                        (take (count (closure-value/closure-inputs operator))
                              scheduled-arg-ids)
                        scheduled-arg-ids)
        argument-values (mapv #(h/strongest-or-nothing network %) scope-arg-ids)
        scope (layers/application-scope (into [operator-answer] argument-values))
        result (cond
      (value/unusable? application-info)
      []

      (not (application-value/application-info? application-info))
      []

      (value/unusable? operator)
      []

      (value/contradiction? operator)
      []

      (operator-value/operator-closure? operator)
      (primitive-application-messages compile*
                                      operator
                                      context-id
                                      scheduled-arg-ids
                                      out-id
                                      network)

      (h/application-activate operator)
      (primitive-application-messages compile*
                                      operator
                                      context-id
                                      scheduled-arg-ids
                                      out-id
                                      network)

      :else
      (closure-application-messages-with compile*
                                         operator-id
                                         args-id
                                         scheduled-arg-ids
                                         out-id
                                         network))]
    (layers/scope-activation-result scope out-id result)))

(defn application-messages
  [application-id operator-id args-id scheduled-arg-ids context-id out-id network]
  (application-messages-with dispatch/default-compiler
                             application-id operator-id args-id scheduled-arg-ids
                             context-id out-id network))

(defn p:apply-application-with
  "Evaluate one retained compiler-2 application object.

  The application object is declaration data. This propagator owns executable
  lowering at evaluation time: primitive operators produce messages directly,
  and closure values delegate to the closure application path."
  [compile* application-id operator-id args-id arg-ids context-id out-id]
  (let [arg-ids (vec arg-ids)
        specs (layers/source-specs application-id operator-id arg-ids)
        possible-base-ids (mapv :base-id specs)
        activate (fn [_inputs _outputs network]
                   (let [pending (layers/pending-base-readers network
                                                       application-id
                                                       specs)]
                     (if (seq pending)
                       (layers/declare-pending-base-readers network
                                                     application-id
                                                     pending)
                       (let [evaluation-ids (mapv #(layers/evaluation-id network %) specs)]
                         (application-messages-with compile*
                                                    application-id
                                                    (first evaluation-ids)
                                                    args-id
                                                    (subvec evaluation-ids 1)
                                                    context-id
                                                    out-id
                                                    network)))))
        inputs (into [application-id operator-id args-id context-id]
                     (concat arg-ids possible-base-ids))]
    (fn [network]
      (let [network* (reduce h/ensure-cell network (conj inputs out-id))
            [prop-id n] ((prop/construct-propagator :compiler-2/apply-application
                                                    activate inputs [out-id])
                         network*)]
        [prop-id
         (net/update-net-dict-entry n
                                    apply-application-props-key
                                    (fnil conj #{})
                                    prop-id)]))))

(defn p:apply-application
  [application-id operator-id args-id arg-ids context-id out-id]
  (p:apply-application-with dispatch/default-compiler
                            application-id operator-id args-id arg-ids
                            context-id out-id))

;; Keep the established entry points while the child-frame runtime owns execution.
(def execute-sub-env-messages-with sub-environment/execute-sub-env-messages-with)
(def execute-sub-env-messages sub-environment/execute-sub-env-messages)
(def p:execute-sub-env-with sub-environment/p:execute-sub-env-with)
(def p:execute-sub-env sub-environment/p:execute-sub-env)

(def application-scope layers/application-scope)
