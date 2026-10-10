(ns propagators.compiler-2.cps-core
  "Canonical stack-safe compiler-2 implementation."
  (:require [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.compiler.handlers :as handlers]
            [propagators.compiler-2.compiler.dispatch :as dispatch]
            [propagators.compiler-2.compiler.predicates :as predicates]
            [propagators.compiler-2.compiler.rewrite :as rewrite]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.language.contract :as contract]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-common.cps :as cps]
            [propagators.compiler-common.core :as common]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.compiler-2.runtime.declaration-effects :as nb]
            [propagators.propagator :as prop]))

(def compiler-result-key common/compiler-result-key)
(def compiler-props-key common/compiler-props-key)
(def compiler-applications-key common/compiler-applications-key)

(def parse-form parser/parse-form)
(def parse-source parser/parse-string)

(def compiler-dispatch
  (cps/compose-rules
   (cps/on predicates/literal? handlers/compile-literal)
   (cps/on predicates/symbol? handlers/compile-symbol)
   (cps/on predicates/sequence? handlers/compile-sequence)
   (cps/on predicates/let-cell?
           (cps/transform-expr rewrite/let-cell->let handlers/compile-let))
   (cps/on predicates/let? handlers/compile-let)
   (cps/on predicates/when-topology? handlers/compile-when-topology)
   (cps/on predicates/network? handlers/compile-network)
   (cps/on predicates/definition? handlers/compile-def)
   handlers/compile-application))

(defn compile-cell-expression [compile-k state expression continuation]
  (compiler-dispatch compile-k state expression
                     (partial handlers/continue-with-cell continuation)))

(def compile* (dispatch/install-default-compiler!
               (cps/make-compiler compile-cell-expression)))
(def default-compiler compile*)

(declare compile-expr)

(defn- prepare-environment
  [network compiler-env prop-ids]
  (cond
    (ids/node-id? compiler-env)
    {:net (nb/ensure-cell network compiler-env)
     :env-id compiler-env
     :prop-ids (vec prop-ids)}

    :else
    (throw
     (ex-info "Compiler environment must be a live environment cell"
              {:environment compiler-env}))))

(defn compile-expr-with-bindings
  ([expr bindings]
   (compile-expr-with-bindings expr bindings {}))
  ([expr bindings {:keys [net seed] :or {net net/empty-net} :as opts}]
   (let [seed (or seed [:compiler-2/program (contract/declaration-key expr)])
         env-id (h/stable-node-id :compiler-2 :root-env seed)
         declared (env/declare-root (nb/begin net) env-id bindings)
         recorded (-> (:net declared)
                      (nb/register-props (:props declared))
                      (nb/register-frame env-id))]
     (compile-expr expr
                   env-id
                   (assoc opts
                          :net recorded
                          :seed seed
                          :environment-props (:props declared))))))

(defn compile-expr
  ([expr] (compile-expr-with-bindings expr (h/default-bindings)))
  ([expr compiler-env] (compile-expr expr compiler-env {}))
  ([expr compiler-env {:keys [net seed path compiler environment-props]
                       :or {net net/empty-net path []}
                       :as opts}]
   (let [syntax (contract/declaration-key expr)
         seed (or seed [:compiler-2/program syntax])
         compile* (or compiler default-compiler)
         {:keys [net env-id prop-ids]}
         (prepare-environment
          (if (net/network-dict-entry net nb/buffer-key) net (nb/begin net))
          compiler-env environment-props)
         [state result]
         (compile* {:net (nb/register-props net prop-ids)
                    :env env-id
                    :seed seed
                    :path path
                    :props prop-ids
                    :applications []
                    :compiler compile*
                    :application/caller (:application/caller opts)
                    :block/premise-context (:block/premise-context opts)
                    :reuse-existing-bindings?
                    (:reuse-existing-bindings? opts)}
                   expr)]
     (common/compiled-map state result))))

(defn compile-source
  ([source] (compile-expr (parser/parse-string source)))
  ([source compiler-env]
   (compile-expr (parser/parse-string source) compiler-env))
  ([source compiler-env opts]
   (compile-expr (parser/parse-string source) compiler-env opts)))

(defn compile-form
  ([form] (compile-expr (parser/parse-form form)))
  ([form environment] (compile-expr (parser/parse-form form) environment))
  ([form environment options]
   (compile-expr (parser/parse-form form) environment options)))

(defn compile-program
  ([forms] (compile-program forms nil {}))
  ([forms environment] (compile-program forms environment {}))
  ([forms environment options]
   (when-not (seq forms)
     (throw (ex-info "A program requires at least one expression" {:forms forms})))
   (let [expression (apply (requiring-resolve 'propagators.compiler-2.language.ast/sequence*)
                           (map parser/parse-form forms))]
     (if environment
       (compile-expr expression environment options)
       (compile-expr-with-bindings expression (h/default-bindings) options)))))

(defn compiled-result [compiled-net]
  (net/network-dict-entry compiled-net compiler-result-key))

(defn compiled-props [compiled-net]
  (net/network-dict-entry compiled-net compiler-props-key))

(defn compiled-applications [compiled-net]
  (net/network-dict-entry compiled-net compiler-applications-key))

(defn p:compile-expr
  [expr-id env-id out-id]
  (prop/construct-propagator
   (prop/concrete-propagator
    (fn [_inputs _outputs network]
      (let [expr (net/network-cell-strongest network expr-id)
            compiled (compile-expr expr env-id
                                   {:net network
                                    :seed [:compile-2 expr-id env-id]})]
        [(message out-id (:net compiled))])))
   [expr-id]
   [out-id]))

(defn p:execute-sub-env
  ([parent-env-id expr-id out-id]
   (p:execute-sub-env parent-env-id expr-id [] (ids/new-node-id) out-id))
  ([parent-env-id expr-id child-env-id out-id]
   (p:execute-sub-env parent-env-id expr-id [] child-env-id out-id))
  ([parent-env-id expr-id watch-ids child-env-id out-id]
   (application/p:execute-sub-env-with default-compiler
                                       parent-env-id expr-id watch-ids
                                       child-env-id out-id)))
