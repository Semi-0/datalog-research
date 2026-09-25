(ns propagators.compiler-2.runtime.operators.relationship-observer
  "Compiler-2 direct installers for native relationship observers."
  (:require [clojure.set :as set]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.compiler.dispatch :as compiler-dispatch]
            [propagators.compiler-2.model.env :as cenv]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship-observer :as observer]))

(defn- compile-form
  [state form role]
  (let [compile* (compiler-dispatch/state-compiler state)
        [state' binding] (compile* (h/child state role) form)]
    [(assoc state' :path (:path state)) binding]))

(defn- propagator-ids
  [network]
  (into #{}
        (keep (fn [[id entry]]
                (when (prop/prop? entry) id)))
        (net/net-env network)))

(defn- observed-and-output-cells
  [state operand-forms]
  (when-not (<= 2 (count operand-forms))
    (throw (ex-info "relationship:roots expects observed cells followed by one output cell"
                    {:operand-forms operand-forms})))
  (let [[compiled bindings]
        (reduce (fn [[current bindings] [index form]]
                  (let [[next-state binding]
                        (compile-form current form [:relationship-roots index])]
                    [next-state (conj bindings binding)]))
                [state []]
                (map-indexed vector operand-forms))
        ids (mapv cenv/binding-id bindings)]
    (when-not (every? some? ids)
      (throw (ex-info "relationship:roots operands must compile to cells"
                      {:operand-forms operand-forms})))
    [compiled (vec (butlast ids)) (last ids)]))

(defn roots-operator
  []
  (operator-value/operator-closure
   {:name 'relationship:roots
    :direct-installer
    (fn [state operand-forms _out-id]
      (let [[state' observed-ids output-id]
            (observed-and-output-cells state operand-forms)
            before (propagator-ids (:net state'))
            [_ installed]
            ((observer/p:observe-roots observed-ids output-id) (:net state'))
            introduced (set/difference (propagator-ids installed) before)]
        [(-> state'
             (assoc :net installed)
             (h/add-props (sort-by pr-str introduced)))
         (cenv/cell-binding output-id)]))}))
