(ns propagators.experimental.visualization.trace
  "Compose pure relationship sampling/projection with explicit TTMS publication."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.operators.relationship-observer :as native]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.experimental.visualization.data :as data]
            [propagators.graph :as graph]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.relationship-observer :as observer]
            [propagators.stdlib.premise-state :as state]))

(defn next-source [source previous base status]
  (let [previous (if (datum/layer-present? previous :premise-state)
                   {:base (datum/layer-value previous :base)
                    :support (state/states-of previous)}
                   previous)
        stamped (publication/next-source-datum source previous base status)]
    (assoc stamped :premise-state (:support stamped))))

(defn- compose-installer [base decorate]
  (operator/operator-closure
   {:name (operator/operator-name base)
    :input-selector (operator/operator-input-selector base)
    :output-selector (operator/operator-output-selector base)
    :install (fn [network arguments fallback]
               (let [[installed prop-ids output] ((operator/operator-install base) network arguments fallback)
                     decorated (reduce (fn [current id]
                                         (decorate current id (net/network-env-lookup current id)))
                                       installed prop-ids)]
                 [decorated prop-ids output]))}))

(defn- stamp-observer [network id p]
  (let [source (first (graph/node-output-ids (graph/get-node (net/net-graph network) id)))
        activate (prop/compose-activation
                  (prop/prop-f p)
                  (publication/stamp-source source :active next-source)
                  (message/lift-message collection/content))]
    (net/assoc-net-prop network id (assoc p :activate activate))))

(defn roots-operator []
  (compose-installer (native/roots-operator) stamp-observer))

(defn- projection-activation [project source output]
  (fn [_ _ network]
    (let [current (net/network-cell-strongest network source)
          content (net/network-cell-content network source)
          graph (data/payload current)]
      (if (value/unusable? current)
        (data/state-messages output [content])
        (let [result (dataflow/project-result project network graph source output)]
          (if (value/contradiction? result)
            ;; This is newly diagnosed projection evidence, not a forwarded
            ;; contradictory input. Preserve its reason at the failing cell.
            [(message/message output
              (collection/content {:base result :support (datum/support-of current)
                                   :premise-state (state/states-of current)}))]
            [(message/message output (data/supported [::dataflow output] result
                                                     #{(data/reference source)} [content]))]))))))

(defn- project-observer [project network id p]
  (let [node (graph/get-node (net/net-graph network) id)
        source (first (graph/node-input-ids node))
        output (first (graph/node-output-ids node))]
    (net/assoc-net-prop network id
                        (assoc p :activate (projection-activation project source output)))))

(defn dataflow-operator []
  (compose-installer (native/dataflow-operator) (partial project-observer dataflow/dataflow-graph)))

(defn roots-semantic-operator []
  (compose-installer
   (native/roots-semantic-operator)
   (fn [network id p]
     (case (prop/prop-name p)
       :relationship/observer (stamp-observer network id p)
       :relationship/semantic-network (project-observer dataflow/semantic-network-graph network id p)
       (throw (ex-info "Unknown semantic observer role" {:id id :name (prop/prop-name p)}))))))

(defn semantic-operator []
  (roots-semantic-operator))
