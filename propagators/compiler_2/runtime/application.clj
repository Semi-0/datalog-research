(ns propagators.compiler-2.runtime.application
  "Compiler-2 closure declarations for canonical accumulating-GUR application."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.compiler.dispatch :as dispatch]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.runtime.sub-environment :as sub-environment]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur.accumulating.core :as gur-core]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as stdlib-prop]))

(def execute-sub-env-props-key sub-environment/execute-sub-env-props-key)

(defn- output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else []))

(defn- ast-children
  [form]
  (cond
    (ast/ast-node? form)
    (map #(obj/slot-value form %) ast/ast-slots)

    (map? form)
    (mapcat identity form)

    (sequential? form)
    form

    (set? form)
    form

    :else
    []))

(defn- ast-references-symbol?
  [form sym]
  (boolean
   (some (fn [candidate]
           (cond
             (and (ast/ast-node? candidate)
                  (= :symbol (ast/type candidate))
                  (= sym (ast/name candidate)))
             true

             :else
             false))
         (tree-seq (comp seq ast-children) ast-children form))))

(defn declare-closure-environment
  "Declare one live closure scope and all of its addressed locals."
  [network lexical-env-id frame-id inputs output-targets input-ids]
  (let [declarations (concat (keep (fn [[sym id]]
                                     (cond
                                       (some? sym) [sym id]
                                       :else nil))
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

(defn- compile-body
  [compile* body env-id state]
  (compile* (assoc state :env env-id :compiler compile*) body))

(defn prepare-closure-topology
  "Compile a closure body against an already-bound lexical environment.

  Returns declaration data only; callers choose transient execution or an
  outer-network topology diff."
  ([network closure-info semantic-body frame-env-id compile-state]
   (prepare-closure-topology dispatch/default-compiler
                             network closure-info semantic-body
                             frame-env-id compile-state))
  ([compile* network closure-info semantic-body frame-env-id compile-state]
   (let [[state result]
         (compile-body compile*
                       semantic-body
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
  (cond
    (and result-id (not= result-id out-inner))
    (let [[prop-id n'] ((stdlib-prop/id result-id out-inner) n)]
      [n' [prop-id]])

    :else [n []]))

(defn- install-output-adapters
  [n result-id output-inners]
  (cond
    (= 1 (count output-inners))
    (install-output-adapter n result-id (first output-inners))

    :else [n []]))

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
       :targets [[(first output-syms) out-id]]
       :route-body-result? true
       :application-result-id out-id}

      (pos? output-count)
      (cond
        (= (count arg-ids) (+ input-count output-count))
        (let [targets (mapv vector
                            output-syms
                            (subvec arg-ids input-count))]
          {:input-ids (subvec arg-ids 0 input-count)
           :targets targets
           :route-body-result? false
           :application-result-id (second (peek targets))})

        :else nil)

      (= (count arg-ids) input-count)
      {:input-ids arg-ids
       :targets [[nil out-id]]
       :route-body-result? true
       :application-result-id out-id}

      :else nil)))

(defn- invalid-closure-call
  [network out-id]
  (let [network* (h/ensure-cell network out-id)
        [prop-id declared]
        ((prop/construct-propagator
          :compiler-2/invalid-closure-call
          (fn [_inputs _outputs _network]
            [(message out-id value/contradiction)])
          []
          [out-id])
         network*)]
    {:net declared
     :prop-ids [prop-id]}))

(defn- compiler-closure-topology
  [compile* declaration-id closure-info semantic-body
   context network arg-ids out-id]
  (let [call-plan (closure-call-plan closure-info arg-ids out-id)]
    (cond
      (nil? call-plan)
      (invalid-closure-call network out-id)

      :else
      (let [{:keys [input-ids targets application-result-id]
             plan-route-body-result? :route-body-result?} call-plan
            route-body-result?
            (cond
              plan-route-body-result?
              true

              (= 1 (count targets))
              (not (ast-references-symbol? semantic-body
                                           (first (first targets))))

              :else
              false)
            frame-id (h/stable-node-id :compiler-2 :gur-closure-scope
                                       declaration-id input-ids targets)
            [environment-props prepared-network]
            (declare-closure-environment
             network
             (closure-value/closure-env closure-info)
             frame-id
             (closure-value/closure-inputs closure-info)
             targets
             input-ids)
            prepared (prepare-closure-topology
                      compile*
                      prepared-network
                      closure-info
                      semantic-body
                      frame-id
                      {:seed [:compiler-2/gur-closure declaration-id
                              input-ids targets]
                       :application/caller (:closure-id context)
                       :gur/frame-context context})
            [declared-network output-props]
            (cond
              route-body-result?
              (install-output-adapters (:net prepared)
                                       (:result-id prepared)
                                       (mapv second targets))

              :else
              [(:net prepared) []])
            [projected-network result-props]
            (install-output-adapter declared-network
                                    application-result-id
                                    out-id)]
        {:net projected-network
         :prop-ids (into (into (vec environment-props)
                               (:props prepared))
                         (concat output-props result-props))}))))

(defn gur-closure
  "Wrap retained Compiler 2 closure IR as a canonical accumulating-GUR value."
  [compile* declaration-id closure-info semantic-body
   captured-topology captured-cell-ids]
  (let [lexical-env-id (closure-value/closure-env closure-info)]
    (assoc
     (gur-core/recursive-closure
      [:compiler-2/closure declaration-id]
      (fn [context network arg-ids out-id]
        (compiler-closure-topology
         compile*
         declaration-id
         closure-info
         semantic-body
         context
         (env/import-captured-lexical-topology network captured-topology)
         (vec arg-ids)
         out-id))
      {:declaration-id declaration-id
       :declaration closure-info
       :captured-cell-ids (vec (distinct (cons lexical-env-id
                                               captured-cell-ids)))
       :declare-frame? gur-core/declare-topology-when-operator-usable})
     closure-value/captured-topology-key
     captured-topology)))

(def execute-sub-env-messages-with sub-environment/execute-sub-env-messages-with)
(def execute-sub-env-messages sub-environment/execute-sub-env-messages)
(def p:execute-sub-env-with sub-environment/p:execute-sub-env-with)
(def p:execute-sub-env sub-environment/p:execute-sub-env)
