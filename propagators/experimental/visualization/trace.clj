(ns propagators.experimental.visualization.trace
  "Compose pure relationship sampling/projection with explicit TTMS publication."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-common.cps :as cps]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator]
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

(defn- declare-observer [install decorate invocation]
  (let [[compile-k state forms fallback k] invocation]
    (cps/compile-args
     compile-k state forms
     (fn [compiled bindings]
       (let [arguments (mapv env/binding-id bindings)
             [network prop-ids output] (install (:net compiled) arguments fallback)
             decorated (reduce (fn [current id]
                                 (decorate current id (net/network-env-lookup current id)))
                               network prop-ids)]
         ;; Compose before add-props records the declaration activation.
         (cps/continue k
                       (basis/add-props (assoc compiled :net decorated) prop-ids)
                       (env/cell-binding output)))))))

(defn- compose-installer [name install decorate]
  (operator/operator-closure
   {:name name
    ;; The existing compiler callback has five protocol arguments. Pack those
    ;; at the boundary; handwritten composition functions stay below four.
    :compiler-operands (comp (partial declare-observer install decorate) vector)}))

(defn- install-roots [network arguments _fallback]
  (if (< (count arguments) 2)
    (throw (ex-info "relationship:roots expects observed cells and an output"
                    {:arguments arguments}))
    (let [output (last arguments)
          [_ installed] ((observer/p:observe-roots (vec (butlast arguments)) output)
                         network)
          introduced (->> (net/net-env installed)
                          (keep (fn [[id entry]]
                                  (if (and (prop/prop? entry)
                                           (not (contains? (net/net-env network) id)))
                                    id
                                    nil)))
                          (sort-by pr-str) vec)]
      [installed introduced output])))

(defn roots-operator []
  (compose-installer
   'relationship:roots install-roots
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

(defn- install-dataflow [network arguments _fallback]
  (if (= 2 (count arguments))
    (let [[source output] arguments
          [id installed] ((dataflow/p:dataflow source output) network)]
      [installed [id] output])
    (throw (ex-info "relationship:dataflow expects input and output cells"
                    {:arguments arguments}))))

(defn dataflow-operator []
  (compose-installer
   'relationship:dataflow install-dataflow
   (fn [network id p]
     (let [node (graph/get-node (net/net-graph network) id)
           source (first (graph/node-input-ids node))
           output (first (graph/node-output-ids node))]
       (net/assoc-net-prop network id
                          (assoc p :activate (projection-activation source output)))))))
