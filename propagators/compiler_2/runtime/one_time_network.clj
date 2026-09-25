(ns propagators.compiler-2.runtime.one-time-network
  "Run one flat-GUR closure against an immutable snapshot and discard its topology."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-patch :as patch]
            [propagators.runner :as runner]))

(defprotocol TraceProjection
  (project-trace [projection completed-net result-id]))

(deftype StrongestProjection []
  TraceProjection
  (project-trace [_ completed-net result-id]
    (net/network-cell-strongest completed-net result-id)))

(defn- sole-parent-id
  [network collection slot-key]
  (let [parents
        (->> (obj/accessor-parent-ids collection slot-key)
             (filter #(contains? (net/net-env network) %))
             vec)]
    (cond
      (= 1 (count parents))
      (first parents)

      (empty? parents)
      nil

      :else
      (throw
       (ex-info
        "Compound projection found ambiguous slot parents"
        {:slot-key slot-key
         :parent-ids parents})))))

(declare pull-cell)

(defn- pull-slot
  [network collection slot-key seen]
  (cond
    (obj/accessor-source-slot-present? collection slot-key)
    (obj/accessor-source-slot-value collection slot-key)

    :else
    (if-let [parent-id (sole-parent-id network collection slot-key)]
      (pull-cell network parent-id seen)
      value/nothing)))

(defn- pull-list
  [network collection seen]
  (let [head (pull-slot network collection :car seen)
        tail-id (sole-parent-id network collection :cdr)]
    (cond
      (nil? tail-id)
      value/contradiction

      :else
      (let [tail (pull-cell network tail-id seen)]
        (cond
          (vector? tail)
          (into [head] tail)

          :else
          value/contradiction)))))

(defn- pull-record
  [network collection seen]
  (into {}
        (map
         (fn [slot-key]
           [slot-key
            (pull-slot network collection slot-key seen)]))
        (sort-by pr-str (obj/accessor-slot-keys collection))))

(defn- pull-cell
  [network cell-id seen]
  (cond
    (contains? seen cell-id)
    value/contradiction

    (not (contains? (net/net-env network) cell-id))
    value/contradiction

    :else
    (let [candidate (net/network-cell-strongest network cell-id)]
      (cond
        (= candidate basis/list-empty-marker)
        []

        (obj/accessor-network? candidate)
        (let [slots (obj/accessor-slot-keys candidate)
              seen (conj seen cell-id)]
          (cond
            (and (contains? slots :car)
                 (contains? slots :cdr))
            (pull-list network candidate seen)

            :else
            (pull-record network candidate seen)))

        :else
        candidate))))

(deftype CompoundProjection []
  TraceProjection
  (project-trace [_ completed-net result-id]
    (pull-cell completed-net result-id #{})))

(defn strongest-projection
  []
  (StrongestProjection.))

(defn compound-projection
  []
  (CompoundProjection.))

(defn- private-id
  [result-id role]
  (gur/stable-node-id
   [:compiler-2 :one-time-network result-id role]))

(defn- assert-private-id!
  [network id role]
  (when (contains? (net/net-env network) id)
    (throw
     (ex-info
      "One-time network private identity collides with active network"
      {:id id :role role})))
  id)

(defn run-flat-gur-once
  [active-net context-id closure-id argument-ids projection result-id]
  (let [snapshot-id
        (assert-private-id!
         active-net
         (private-id result-id :snapshot)
         :snapshot)

        inner-result-id
        (assert-private-id!
         active-net
         (private-id result-id :result)
         :result)

        snapshot
        (observation/frozen-snapshot active-net)

        prepared
        (-> active-net
            gur/vm-net
            (nb/install-cell snapshot-id snapshot snapshot)
            (nb/ensure-cell inner-result-id))

        effect
        (gur/apply-closure-effect
         closure-id
         (into [context-id snapshot-id] argument-ids)
         inner-result-id)

        [tasks declared] (patch/apply-root-patch effect prepared)

        completed (runner/completed-network
                   (runner/run-network tasks declared))]
    {:value
     (project-trace projection completed inner-result-id)

     :temporary-net
     completed

     :temporary-net-disposed?
     true}))

(defn- usable-inputs?
  [network input-ids]
  (every?
   (fn [id]
     (and
      (contains? (net/net-env network) id)
      (not
       (value/unusable?
        (net/network-cell-strongest network id)))))
   input-ids))

(defn one-time-operator
  ([]
   (one-time-operator (compound-projection)))
  ([projection]
   (operator-value/propagator-operator
    {:name 'run-flat-gur-once
     :input-selector
     (fn [arg-ids _fallback-id _context-id]
       (let [arguments (vec arg-ids)]
         (cond
           (< (count arguments) 2)
           (throw
            (ex-info
             "run-flat-gur-once expects trigger, closure, and optional arguments"
             {:arg-ids arguments}))

           :else
           arguments)))
     :activate
     (fn [network inputs outputs context-id]
       (let [[_trigger-id closure-id & argument-ids] inputs
             [result-id] outputs]
         (cond
           (not (usable-inputs? network inputs))
           []

           :else
           (let [{:keys [value]}
                 (run-flat-gur-once
                  network
                  context-id
                  closure-id
                  argument-ids
                  projection
                  result-id)]
             [(message result-id value)]))))})))

(defn one-time-environment
  []
  [['run-flat-gur-once (one-time-operator)]])

(defn trace-environment
  []
  (into
   (observation/observation-environment)
   (one-time-environment)))
