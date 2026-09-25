(ns propagators.compiler-2.runtime.operators.visualizer
  "Compiler-2 direct installers for port-neutral visualizer propagators."
  (:require [clojure.set :as set]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.compiler.dispatch :as compiler-dispatch]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.env :as cenv]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.visualizer :as visualizer]))

(defn- compile-form
  [state form role]
  (let [compile* (compiler-dispatch/state-compiler state)
        [state' binding] (compile* (h/child state role) form)]
    [(assoc state' :path (:path state)) binding]))

(defn- compile-cell-forms
  [state forms role]
  (reduce (fn [[current ids] [index form]]
            (let [[next-state binding]
                  (compile-form current form [role index])
                  id (cenv/binding-id binding)]
              (when-not id
                (throw (ex-info "visualizer operand must compile to a cell"
                                {:operator role :form form :binding binding})))
              [next-state (conj ids id)]))
          [state []]
          (map-indexed vector forms)))

(defn- propagator-ids
  [network]
  (into #{}
        (keep (fn [[id entry]]
                (when (prop/prop? entry) id)))
        (net/net-env network)))

(defn- install
  [state installer result-id]
  (let [before (propagator-ids (:net state))
        [_ installed] (installer (:net state))
        introduced (set/difference (propagator-ids installed) before)]
    [(-> state
         (assoc :net installed)
         (h/add-props (sort-by pr-str introduced)))
     (cenv/cell-binding result-id)]))

(defn- fixed-cell-operator
  [name arity installer]
  (operator-value/operator-closure
   {:name name
    :direct-installer
    (fn [state operand-forms _out-id]
      (when-not (= arity (count operand-forms))
        (throw (ex-info (str name " expects " arity " cell operands")
                        {:operator name :operand-forms operand-forms})))
      (let [[state' ids] (compile-cell-forms state operand-forms name)
            result-id (last ids)]
        (install state' (apply installer ids) result-id)))}))

(defn cell-window-operator
  []
  (fixed-cell-operator 'cell-window 2 visualizer/p:cell-window))

(defn cell-history-operator
  []
  (fixed-cell-operator 'cell-history 2 visualizer/p:cell-history))

(defn hierarchy-operator
  []
  (fixed-cell-operator 'hierarchy 2 visualizer/p:hierarchy))

(defn propagator-references-operator
  []
  (operator-value/operator-closure
   {:name 'propagator-references
    :direct-installer
    (fn [state operand-forms _out-id]
      (when-not (= 3 (count operand-forms))
        (throw (ex-info "propagator-references expects cell, direction, and output cell"
                        {:operand-forms operand-forms})))
      (let [[cell-form direction-form output-form] operand-forms
            direction (when (= :literal (ast/type direction-form))
                        (ast/value direction-form))]
        (when-not (contains? visualizer/directions direction)
          (throw (ex-info "propagator-references direction must be literal :inputs or :outputs"
                          {:direction-form direction-form})))
        (let [[state' [cell-id output-id]]
              (compile-cell-forms state [cell-form output-form]
                                  'propagator-references)]
          (install state'
                   (visualizer/p:propagator-references cell-id direction output-id)
                   output-id))))}))

(defn juxtapose-operator
  []
  (operator-value/operator-closure
   {:name 'juxtapose
    :direct-installer
    (fn [state operand-forms _out-id]
      (when-not (<= 3 (count operand-forms))
        (throw (ex-info "juxtapose expects at least two view cells and one output cell"
                        {:operand-forms operand-forms})))
      (let [[state' ids] (compile-cell-forms state operand-forms 'juxtapose)
            output-id (last ids)]
        (install state'
                 (visualizer/p:juxtapose (butlast ids) output-id)
                 output-id)))}))
