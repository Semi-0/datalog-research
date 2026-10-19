(ns examples.lain.top-down.scanner
  "Opt-in checkpoint observations. Declared roles distinguish holes from inputs."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.propagator :as prop]))

(defn scan-node [snapshot node label role]
  (when-not (string? label) (throw (ex-info "Scanner labels must be strings" {})))
  (when-not (and (satisfies? observation/NetworkSnapshot snapshot)
                 (satisfies? observation/RelationshipSnapshot snapshot))
    (throw (ex-info "Scanner requires a frozen readable snapshot" {})))
  (when-not (contains? #{:implementation :derived :input} role)
    (throw (ex-info "Declare an implementation, derived-value or input role" {:role role})))
  (let [info (observation/snapshot-node-info snapshot node)
        current (observation/snapshot-value snapshot node)
        neighbors (observation/snapshot-neighbors snapshot node)
        status (cond
                 (value/contradiction? current) :contradiction
                 (value/nothing? current) (case role
                                           :implementation :unresolved-implementation
                                           :derived :waiting
                                           :input :awaiting-input)
                 (value/unusable? current) :unusable
                 :else :available)]
    {:name (str label) :role role :status status :kind (:node/kind info)
     :input-count (count (:node/inputs neighbors)) :consumer-count (count (:node/outputs neighbors))}))

(defn primitive-bindings []
  [['top-down:scan-node
    (ttms/scalar-operator 'top-down:scan-node
                          (prop/primitive-propagator ::scan-node scan-node))]])
