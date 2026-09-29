(ns propagators.experimental.ttms-accessor-switch
  "Unpromoted accessor-route experiment. Two existing TTMS switches connect an
  already-addressed slot port and participant under the collection's support.
  This does not replace compound routing or install into the default environment."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.ttms-branching :as branching]
            [propagators.experimental.ttms-primitives :as primitives]
            [propagators.ids :as ids]
            [propagators.propagator :as prop]))

(defn install-bidirectional-switch
  "Connect two ports through existing layered switch applications.
  Returns {:network Net :tasks [propagator-id ...]}; execution is caller-owned."
  [network enabled left right]
  (let [switch (get (into {} (extension/extension-bindings branching/session-extension))
                    'switch)
        install (operator/operator-install switch)
        [n1 forward _] (install network [left enabled] right)
        [n2 backward _] (install n1 [right enabled] left)]
    {:network n2 :tasks (into (vec forward) backward)}))

(defn install-collection-switch
  "Derive the route condition from the whole supported collection, then install
  both directions. No source epochs are created; no support is stripped.
  Ports are supplied explicitly, so collection topology ownership is unchanged."
  [network collection slot-port participant]
  (let [enabled (ids/new-node-id)
        present (primitives/scalar-operator
                 ::collection-present
                 (prop/primitive-propagator
                  ::collection-present
                  (fn [base]
                    (cond
                      (value/nothing? base) value/nothing
                      (value/contradiction? base) base
                      :else true))))
        [prepared condition-tasks _]
        ((operator/operator-install present) network [collection] enabled)
        {:keys [network tasks]}
        (install-bidirectional-switch prepared enabled slot-port participant)]
    {:network network :tasks (into (vec condition-tasks) tasks) :enabled enabled}))
