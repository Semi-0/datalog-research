(ns propagators.experimental.visualization.trace
  "Compose pure relationship sampling/projection with explicit TTMS publication."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.operators.relationship-observer :as operators]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.experimental.visualization.data :as data]
            [propagators.graph :as graph]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.stdlib.premise-state :as state]))

(defn next-source [source previous base status]
  (let [previous (if (datum/layer-present? previous :premise-state)
                   {:base (datum/layer-value previous :base)
                    :support (state/states-of previous)}
                   previous)
        stamped (publication/next-source-datum source previous base status)]
    (assoc stamped :premise-state (:support stamped))))

(defn- compose-installer [original target-name decorate]
  (operator/operator-closure
   {:name (operator/operator-name original)
    :direct-installer
    (fn [s forms out]
      (let [before (net/net-env (:net s))
            [installed binding] ((operator/operator-direct-installer original) s forms out)
            network (:net installed)
            updated (reduce-kv
                     (fn [n id p]
                       (if (and (prop/prop? p) (= target-name (prop/prop-name p))
                                (not (contains? before id)))
                         (decorate n id p)
                         n))
                     network (net/net-env network))]
        [(assoc installed :net updated) binding]))}))

(defn roots-operator []
  (compose-installer
   (operators/roots-operator) :relationship/observer
   (fn [network id p]
     (let [source (first (graph/node-output-ids (graph/get-node (net/net-graph network) id)))
           activate (prop/compose-activation
                     (prop/prop-f p)
                     (publication/stamp-source source :active next-source)
                     (message/lift-message collection/content))]
       (net/assoc-net-prop network id (assoc p :activate activate))))))

(defn- projection-activation [source output]
  (fn [_ _ network]
    (let [current (net/network-cell-strongest network source)
          content (net/network-cell-content network source)
          graph (data/payload current)]
      (if (value/unusable? current)
        (data/state-messages output [content])
        (let [result (dataflow/project-result dataflow/dataflow-graph network graph source output)]
          (if (value/contradiction? result)
            ;; This is newly diagnosed projection evidence, not a forwarded
            ;; contradictory input. Preserve its reason at the failing cell.
            [(message/message output
              (collection/content {:base result :support (datum/support-of current)
                                   :premise-state (state/states-of current)}))]
            [(message/message output (data/supported [::dataflow output] result
                                                     #{(data/reference source)} [content]))]))))))

(defn dataflow-operator []
  (compose-installer
   (operators/dataflow-operator) :relationship/dataflow
   (fn [network id p]
     (let [node (graph/get-node (net/net-graph network) id)
           source (first (graph/node-input-ids node))
           output (first (graph/node-output-ids node))]
       (net/assoc-net-prop network id
                          (assoc p :activate (projection-activation source output)))))))
