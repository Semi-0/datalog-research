(ns propagators.compiler-2.runtime.sub-environment
  "Compile and execute a child lexical frame, publishing its topology and result."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.compiler.dispatch :as dispatch]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.activation :as activation]
            [propagators.compiler-2.runtime.topology-effects :as topology-effects]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def execute-sub-env-props-key :compiler-2/execute-sub-env-props)

(defn- cell-content-or-nothing
  [network id]
  (if (contains? (net/net-env network) id)
    (net/network-cell-content network id)
    value/nothing))

(defn- declare-child-environment
  [network parent-env-id parent-env child-env-id]
  (let [runtime-parent-id (h/stable-node-id :compiler-2
                                            :execute-sub-env
                                            parent-env-id
                                            child-env-id
                                            :parent)
        [imported _] (env/import-environment network runtime-parent-id parent-env)
        [props declared] ((env/p:scope-frame runtime-parent-id child-env-id)
                          (h/ensure-cell imported child-env-id))]
    [props declared]))

(defn- compile-expr
  [compile* expr child-env network seed props]
  (compile*
   {:net network
    :env child-env
    :seed seed
    :path []
    :props (vec props)
    :applications []
    :compiler compile*}
   expr))

(defn execute-sub-env-messages-with
  [compile* parent-env-id expr-id child-env-id out-id network]
  (let [expr (h/strongest-or-nothing network expr-id)
        parent-env (h/strongest-or-nothing network parent-env-id)]
    (if (or (value/unusable? expr)
            (value/unusable? parent-env))
      []
      (let [[env-props with-child]
            (declare-child-environment network parent-env-id parent-env child-env-id)
            [state result]
            (compile-expr compile* expr child-env-id with-child
                          [:compiler-2/execute-sub-env
                           parent-env-id expr-id child-env-id out-id]
                          env-props)
            result-id (env/binding-id result)
            after-body (activation/run-network (:net state) [] (:props state))
            result-value (h/strongest-or-nothing after-body result-id)
            result-content (cell-content-or-nothing after-body result-id)
            output-content (if (value/unusable? result-content)
                             result-value
                             result-content)
            diff (topology-effects/network-diff network after-body (:props state))]
        (if (value/unusable? output-content)
          diff
          (update diff :messages conj (message out-id output-content)))))))

(defn execute-sub-env-messages
  [parent-env-id expr-id child-env-id out-id network]
  (execute-sub-env-messages-with dispatch/default-compiler
                                 parent-env-id expr-id child-env-id out-id
                                 network))

(defn p:execute-sub-env-with
  [compile* parent-env-id expr-id watch-ids child-env-id out-id]
  (let [watch-ids (vec watch-ids)
        inputs (into [parent-env-id expr-id] watch-ids)
        outputs [child-env-id out-id]
        activate (fn [_inputs _outputs network]
                   (execute-sub-env-messages-with compile*
                                                  parent-env-id
                                                  expr-id
                                                  child-env-id
                                                  out-id
                                                  network))]
    (fn [network]
      (let [network* (reduce h/ensure-cell network (into inputs outputs))
            [prop-id n] ((prop/construct-propagator :compiler-2/execute-sub-env
                                                    activate inputs outputs)
                         network*)]
        [prop-id
         (net/update-net-dict-entry n
                                    execute-sub-env-props-key
                                    (fnil conj #{})
                                    prop-id)]))))

(defn p:execute-sub-env
  "Compile and run one expression in a child compiler-2 env.

  `watch-ids` is the minimal v1 reactivity hook: pass external cells that should
  re-trigger this transient execution when their strongest values change.
  "
  ([parent-env-id expr-id out-id]
   (p:execute-sub-env parent-env-id expr-id [] (ids/new-node-id) out-id))
  ([parent-env-id expr-id child-env-id out-id]
   (p:execute-sub-env parent-env-id expr-id [] child-env-id out-id))
  ([parent-env-id expr-id watch-ids child-env-id out-id]
   (p:execute-sub-env-with dispatch/default-compiler
                           parent-env-id expr-id watch-ids child-env-id out-id)))
