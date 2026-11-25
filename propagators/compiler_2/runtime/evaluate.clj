(ns propagators.compiler-2.runtime.evaluate
  "Compile an available data expression into the caller's running network."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.dispatch :as dispatch]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.language.quotation :as q]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.runtime.returned-outputs :as outputs]
            [propagators.compiler-2.runtime.topology-effects :as topology]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net])
  (:import [propagators.compiler_2.language.quotation LexicalEnvironment]))

(defn- compile-result [network key frame form out]
  (let [compile* dispatch/default-compiler
        [state result] (compile* {:net network :env frame :seed key :path []
                                  :props [] :applications [] :compiler compile*}
                                 (parser/parse-form form))
        boundary (outputs/copy-output-boundary nil key (env/binding-id result) out)]
    (update state :net #(reduce declarations/emit-effect % boundary))))

(declare evaluate)

(defn- watch-members [network members args out]
  (for [id members
        :let [key [::member out id]]
        :when (not (topology/declared? network key))]
    [(gur/bind-name topology/declaration-scope key id)
     (gur/declare-prop (gur/stable-node-id key) [id] [out]
                       (fn [_ _ current] (evaluate current args [out] nil)))]))

(defn- evaluate [network [expression environment :as args] [out] _]
  (let [scope (q/base (net/network-cell-strongest network environment))
        {:keys [form members]} (q/read-expression network expression)
        result (cond
                 (or (value/contradiction? scope) (value/contradiction? form))
                 {:messages [(message out value/contradiction)]}
                 (or (value/nothing? scope) (value/nothing? form)) {}
                 (not (and (instance? LexicalEnvironment scope)
                           (contains? (net/net-env network) (:id scope))))
                 (throw (ex-info "eval requires a live (current-environment)" {}))
                 :else
                 (let [key [::expression out (:id scope) form]]
                   (topology/declare-once network key out
                                          #(compile-result % key (:id scope) form out))))]
    (update result :effects #(into (vec %) cat (watch-members network members args out)))))

(defn eval-operator []
  (operator/propagator-operator
   {:name 'eval
    :input-selector (fn [args _ _]
                      (when-not (= 2 (count args))
                        (throw (ex-info "eval expects an expression and an environment" {})))
                      args)
    :activate evaluate}))
