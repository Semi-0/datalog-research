(ns propagators.compiler-2.runtime.operators.combinator
  "Compose ordinary propagator applications without resolving their input values."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.declaration-effects :as nb]
            [propagators.compiler-2.runtime.operators.bounded-history :as bounded-history]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.compound-object :as obj]
            [propagators.experimental.ttms-primitives :as primitives]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.observation :as observation]))

(defn- require-arity [name arguments expected]
  (when-not (= expected (count arguments))
    (throw (ex-info (str name " expects " expected " arguments")
                    {:operator name :arguments arguments}))))

(defn- copy-effect [scope source target]
  (gur/declare-prop (gur/stable-node-id [scope source target])
                    [::connection scope source target]
                    [source] [target] observation/activation))

(defn- identity-effects [scope source target]
  (if (= source target)
    []
    [(copy-effect scope source target) (copy-effect scope target source)]))

(defn- chain-effects [context input stages output]
  (if (empty? stages)
    (identity-effects [::identity context output] input output)
    (loop [source input remaining (seq stages) index 0 effects []]
      (let [last? (nil? (next remaining))
            target (if last? output
                       (gur/stable-node-id [::pipe context output index]))
            effects (cond-> effects
                      (not last?) (conj (gur/declare-cell target)))
            effects (conj effects
                          (gur/apply-closure-effect (first remaining)
                                                    [context source] target))]
        (if last?
          effects
          (recur target (next remaining) (inc index) effects))))))

(def pipe-operator
  (operator/operator-closure
   {:name '|>
    :compiler-activate
    (fn [_ _network context arguments output]
      (when (empty? arguments)
        (throw (ex-info "|> expects an input followed by unary stages" {})))
      {:effects (chain-effects context (first arguments) (rest arguments) output)})}))

(defn- construction-messages [network output fingerprint build]
  (let [previous (data/payload (net/network-cell-strongest network output))]
    (cond
      (= fingerprint (::construction (meta previous))) []
      (value/nothing? previous)
      (let [stage (build)]
        [(message output (with-meta stage (assoc (meta stage) ::construction fingerprint)))])
      :else
      (throw (ex-info "A constructed stage has fixed configuration; construct a new stage to change it"
                      {:configuration fingerprint})))))

(defn- factory-operator [name build]
  (operator/operator-closure
   {:name name
    :compiler-activate
    (fn [_ network _context arguments output]
      (require-arity name arguments 1)
      (let [configuration (data/payload (net/network-cell-strongest network (first arguments)))]
        (cond
          (value/contradiction? configuration) {:messages [(message output configuration)]}
          (value/nothing? configuration) {:messages []}
          :else {:messages (construction-messages network output [name configuration]
                                                  #(build configuration))})))}))

(defn- composed-operator [stages]
  (operator/operator-closure
   {:name 'compose
    :compiler-activate
    (fn [_ _network context arguments output]
      (require-arity 'compose arguments 1)
      {:effects (chain-effects context (first arguments) stages output)})}))

(def compose-operator
  (operator/operator-closure
   {:name 'compose
    :compiler-activate
    (fn [_ network _context arguments output]
      {:messages (construction-messages network output [::compose (vec arguments)]
                                        #(composed-operator (reverse arguments)))})}))

(defn- slot-stage [key]
  (when-not (or (keyword? key) (symbol? key) (string? key))
    (throw (ex-info "slot expects a keyword, symbol or string key" {:key key})))
  (operator/operator-closure
   {:name ['slot key]
    :install
    (fn [network arguments output]
      (require-arity 'slot arguments 1)
      (let [owner (first arguments)
            prepared (reduce nb/ensure-cell network [owner output])
            [id installed] ((obj/p:slot key output owner) prepared)]
        [installed [id] output]))}))

(defn- scalar [name f]
  (primitives/scalar-operator name (prop/concrete-primitive-propagator name f)))

(def ^:private boolean-operator
  (scalar 'combinator:boolean
          (fn [candidate]
            (cond
              (contains? #{true :bool4/true} candidate) candidate
              (contains? #{false :bool4/false} candidate) candidate
              :else (throw (ex-info "switch predicate must return a boolean" {:value candidate}))))))

(def ^:private core-switch
  (get (into {} (basis/default-bindings)) 'switch))

(defn- filter-stage [predicate]
  (let [check-boolean boolean-operator
        ordinary-switch core-switch]
    (operator/operator-closure
     {:name ['switch predicate]
      :compiler-activate
      (fn [_ _network context arguments output]
        (require-arity 'switch arguments 1)
        (let [input (first arguments)
              key [::switch context predicate input output]
              condition (gur/stable-node-id [key :condition])
              checked (gur/stable-node-id [key :checked])
              checker (gur/stable-node-id [key :checker])
              gate (gur/stable-node-id [key :gate])]
          {:messages [(message checker check-boolean) (message gate ordinary-switch)]
           :effects [(gur/declare-cell condition)
                     (gur/declare-cell checked)
                     (gur/declare-cell checker)
                     (gur/declare-cell gate)
                     (gur/apply-closure-effect predicate [context input] condition)
                     (gur/apply-closure-effect checker [context condition] checked)
                     (gur/apply-closure-effect gate [context input checked] output)]}))})))

(def switch-operator
  (let [ordinary-switch core-switch]
    (operator/operator-closure
     {:name 'switch
      :compiler-activate
      (fn [_ network context arguments output]
        (case (count arguments)
          1 {:messages (construction-messages network output [::switch (first arguments)]
                                              #(filter-stage (first arguments)))}
          2 (let [key [::ordinary-switch context output]
                  callable (gur/stable-node-id key)]
              {:messages [(message callable ordinary-switch)]
               :effects [(gur/declare-cell callable)
                         (gur/apply-closure-effect callable (into [context] arguments) output)]})
          (throw (ex-info "switch expects a predicate, or the existing value+condition form"
                          {:arguments arguments}))))})))

(defn primitive-bindings []
  [['combinator:pipe pipe-operator]
   ['combinator:compose compose-operator]
   ['combinator:slot (factory-operator 'slot slot-stage)]
   ['combinator:switch switch-operator]
   ['combinator:history (factory-operator 'history bounded-history/history-stage)]
   ['switch switch-operator]])

(def session-extension
  (extension/extension-bundle {:id ::combinators :bindings (primitive-bindings) :effects []}))
