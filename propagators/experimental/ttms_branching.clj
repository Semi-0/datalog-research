(ns propagators.experimental.ttms-branching
  "Opt-in branching: select arguments, apply layers, then lift messages.
  Selection never alters premise statuses or timestamps."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.premise-publication :as publication]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.support :as support]
            [propagators.stdlib.premise-state :as state]))

(defn- base-of [x]
  (if (datum/layer-present? x :base) (datum/layer-value x :base) x))

(def last-base-procedure
  {:net net/empty-net
   :f (fn [_ inputs outputs network]
        (second ((apply (prop/primitive-propagator ::last-base
                         (fn [& bases] (last bases)))
                        (concat inputs outputs)) network)))})

(defn- install-procedure [network]
  (let [[procedure base-id support-id state-id] (repeatedly 4 ids/new-node-id)
        prepared (-> network
                     (nb/install-cell base-id last-base-procedure last-base-procedure)
                     (nb/install-cell support-id support/procedure support/procedure)
                     (nb/install-cell state-id state/procedure state/procedure))
        base (layered/install-layered-procedure! prepared procedure :base base-id)
        supported (layered/install-layered-procedure! (:net base) procedure :support support-id)
        stateful (layered/install-layered-procedure! (:net supported) procedure :premise-state state-id)]
    [(:net stateful) procedure]))

(defn- publish [result]
  ;; With only plain arguments the runtime runs only the base layer. Its support
  ;; is explicitly empty, not a freshly invented source. Extra layers still fail.
  (if (datum/layer-present? result :premise-state)
    (publication/content result)
    (collection/content
   (if (datum/layer-present? result :support)
     result
     {:base (base-of result) :support #{}}))))

(defn- application-activation [procedure arguments output]
  ;; Use the public installer to obtain its activation once. This scratch graph
  ;; is discarded: only the selector is installed into the caller's topology.
  (let [prepared (nb/install-cells (concat [procedure output] arguments))
        [id scratch] ((layered/p:apply-layered procedure arguments output) prepared)]
    (prop/compose-activation (prop/prop-f (net/network-env-lookup scratch id))
                             (message/lift-message publish))))

(defn- output-activation [procedure condition then-input else-input output]
  (let [pending (application-activation procedure [condition] output)
        then (application-activation procedure [condition then-input] output)
        otherwise (application-activation procedure [condition else-input] output)]
    (fn [inputs outputs network]
      (let [condition-value (net/network-cell-strongest network condition)
            activate (cond
                       (value/unusable? condition-value) pending
                       (base-of condition-value) then
                       :else otherwise)]
        (activate inputs outputs network)))))

(defn- install-call [kind original network arguments fallback]
  (let [{:keys [inputs outputs out-id]} (operator/operator-call original arguments fallback nil)
        prepared (reduce nb/ensure-cell network (concat inputs outputs))
        [prepared procedure] (install-procedure prepared)
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
        activations (mapv #(apply output-activation procedure %) selections)
        activate (prop/compose-activation
                  (fn [inputs outputs current]
                    (into [] (mapcat #(% inputs outputs current)) activations))
                  publication/transport-states)
        [id installed] ((prop/construct-propagator
                         [::branching kind] activate
                         (into [procedure nothing-id] inputs) outputs) prepared)]
    [installed [id] out-id]))

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
    :bindings [['if (branching-operator :if (basis/if-operator))]
               ['switch (branching-operator :switch (basis/switch-operator))]
               ['branch (branching-operator :branch (basis/branch-operator))]]
    :effects []}))
