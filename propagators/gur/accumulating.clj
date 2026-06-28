(ns propagators.gur.accumulating
  "Public surface for the main accumulating GUR implementation.

  Core idea:
  - applying a recursive closure emits frame/topology facts into one owner cell;
  - a runner propagator owns evaluation state and drains those facts;
  - source DSL helpers are only syntax over that declaration/evaluation split.
  "
  (:require [propagators.gur.accumulating.core :as core]
            [propagators.gur.accumulating.facts :as facts]
            [propagators.gur.accumulating.runner :as runner]
            [propagators.gur.accumulating.source :as source]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(def recursive-closure-tag core/recursive-closure-tag)
(def frame-index-key facts/frame-index-key)
(def frame-prop-index-key facts/frame-prop-index-key)
(def task-index-key facts/task-index-key)
(def application-request-index-key facts/application-request-index-key)

(def add-task-facts facts/add-task-facts)
(def application-key facts/application-key)
(def application-request-fragment facts/application-request-fragment)
(def application-requests facts/application-requests)
(def recursive-closure core/recursive-closure)
(def recursive-closure? core/recursive-closure?)
(def strongest-or-nothing core/strongest-or-nothing)
(def p:accumulate-apply-closure core/p:accumulate-apply-closure)
(def p:when-topology core/p:when-topology)

(def p:run-accumulated-network runner/p:run-accumulated-network)

(def contextual-installers source/contextual-installers)
(def default-installers source/default-installers)
(def recursive-definition source/recursive-definition)
(def source-recursive-closure source/source-recursive-closure)

(defmacro def-recursive
  [& args]
  `(source/def-recursive ~@args))

(defn p:apply-closure
  [closure-id arg-ids out-id]
  (let [arg-ids (vec arg-ids)
        applied-net-id (ids/new-node-id)]
    (fn [network]
      (let [n0 (reduce nb/ensure-cell network
                       (conj (into [closure-id applied-net-id] arg-ids) out-id))
            [apply-prop n1] ((core/p:accumulate-apply-closure closure-id
                                                                 arg-ids
                                                                 applied-net-id
                                                                 out-id)
                             n0)
            [runner-prop n2] ((runner/p:run-accumulated-network applied-net-id
                                                                 (into [closure-id] arg-ids)
                                                                 [out-id])
                              n1)]
        [[apply-prop runner-prop]
         (net/assoc-net-dict-entry n2
                                   (facts/application-key closure-id arg-ids out-id)
                                   applied-net-id)]))))
