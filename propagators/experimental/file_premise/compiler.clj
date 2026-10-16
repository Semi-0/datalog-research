(ns propagators.experimental.file-premise.compiler
  "Opt-in network declarations supported by one file-owned premise cell."
  (:require [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.compiler.handlers :as handlers]
            [propagators.compiler-2.compiler.predicates :as predicates]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-common.cps :as cps]
            [propagators.experimental.ttms-application :as native]
            [propagators.ids :as ids]))

(defn file-compiler
  "Compose a network rule with the ordinary compiler. Delayed network bodies
  retain this compiler, so nested declarations inherit the same file premise."
  [source-id]
  (if (ids/node-id? source-id)
    nil
    (throw (ex-info "File premise must be a cell ID" {:source-id source-id})))
  (let [constructor (get (into {} (extension/extension-bindings native/session-extension))
                         'ttms-premise-closure)
        install (operator/operator-install constructor)]
    (cps/make-compiler
     (cps/compose-rules
      (cps/on predicates/network?
              (fn [compile-k state expression k]
                (let [path (:path state)]
                  (handlers/compile-network
                   compile-k (h/child state ::raw-network) expression
                   (fn [compiled raw]
                     (let [[prepared output] (h/new-cell (assoc compiled :path path)
                                                         ::supported-network)
                           [network props selected]
                           (install (:net prepared) [(env/binding-id raw) source-id]
                                    (env/binding-id output))]
                       (cps/continue k (-> prepared (assoc :net network) (h/add-props props))
                                     (env/cell-binding selected))))))))
      compiler/compiler-dispatch))))
