(ns propagators.experimental.ttms-branching
  "Compatibility branching bindings composed from the shared conditional selector."
  (:require [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.stdlib.conditional :as conditional]))

(defn- install-call [kind original network arguments fallback]
  (let [{:keys [inputs outputs out-id]} (operator/operator-call original arguments fallback nil)
        prepared (reduce nb/ensure-cell network (concat inputs outputs))
        nothing-id (ids/new-node-id)
        prepared (nb/install-cell prepared nothing-id)
        selections (case kind
                     :if [(conj (vec inputs) (first outputs))]
                     :switch (let [[input condition] inputs]
                               [[condition input nothing-id (first outputs)]])
                     :branch (let [[condition then-input else-input] inputs
                                   [then-output else-output] outputs]
                               [[condition then-input nothing-id then-output]
                                [condition nothing-id else-input else-output]])
                     (throw (ex-info "Unknown branching primitive" {:kind kind})))
        [installed props] (conditional/install-outputs prepared selections)]
    [installed props out-id]))

(defn- branching-operator [kind original]
  (operator/operator-closure
   {:name [::branching kind]
    :input-selector (operator/operator-input-selector original)
    :output-selector (operator/operator-output-selector original)
    :install (fn [network arguments output]
               (install-call kind original network arguments output))}))

(def session-extension
  (extension/extension-bundle
   {:id ::branching
    :bindings [['select (branching-operator :if (basis/select-operator))]
               ['switch (branching-operator :switch (basis/switch-operator))]
               ['branch (branching-operator :branch (basis/branch-operator))]]
    :effects []}))
