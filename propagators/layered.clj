(ns propagators.layered
  "Layered data/procedure support built from compound-object slots."
  (:require [clojure.set :as set]
            [propagators.cells.cell :as cell]
            [propagators.cells.diff :as diff]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.named-network :as named]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn p:layer
  "Bidirectional sync between a layer value cell and a layered object slot."
  [layer-name layer-value-id layered-object-id]
  (obj/p:slot layer-name layer-value-id layered-object-id))

(defn p:base
  [base-value-id layered-object-id]
  (p:layer :base base-value-id layered-object-id))

(defn p:layered-procedure
  "Merge one procedure-network extension cell into a layered procedure cell."
  [proc-id extension-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [extension (net/network-cell-strongest network extension-id)]
       (if (value/unusable? extension)
         []
         [(message proc-id extension)])))
   [extension-id]
   [proc-id]))

(declare p:apply-layered)

(defn p:layered-operator
  "Create a propagator installer backed by a layered-procedure cell."
  [layered-procedure-id]
  (fn [& node-ids]
    (let [nodes (vec node-ids)
          args (vec (butlast nodes))
          out (last nodes)]
      (p:apply-layered layered-procedure-id args out))))

(defn- layer-object-net
  [layer->value]
  (reduce
   (fn [n [layer-name v]]
     (nb/add-named-cell n layer-name v))
   (net/net-with-dict net/empty-net {:slot-index {}})
   layer->value))

(defn- normalize-layered-value
  [v]
  (cond
    (named/named-network? v) v
    (value/nothing? v) (net/net-with-dict net/empty-net {:slot-index {}})
    :else (layer-object-net {:base v})))

(defn- ignored-dict-key?
  [k]
  (or (= k :slot-index)
      (and (vector? k)
           (= (first k) obj/slot-sync-key))
      (ids/node-id? k)))

(defn- available-branches
  [v]
  (if (named/named-network? v)
    (->> (keys (net/net-dict-or-empty v))
         (remove ignored-dict-key?)
         (filter #(not (value/nothing?
                         (net/network-cell-strongest v (net/network-dict-entry v %)))))
         set)
    #{:base}))

(defn- layer-present?
  [v layer-name]
  (contains? (available-branches v) layer-name))

(defn- install-local-cell
  [n id outer-net]
  (let [entry (net/network-env-lookup outer-net id)
        strongest (cell/cell-strongest entry)]
    (nb/install-cell n id
                     (normalize-layered-value strongest)
                     (normalize-layered-value strongest))))

(defn- install-layer-closure-cell
  [n proc-id layer-name]
  (let [closure-id (ids/new-node-id)
        [slot-prop-id n'] ((p:layer layer-name closure-id proc-id) (nb/install-cell n closure-id))]
    {:closure-id closure-id
     :slot-prop-id slot-prop-id
     :network n'}))

(defn- install-base-layer
  [n proc-id arg-ids out-id layer-prop-ids]
  (let [{:keys [closure-id slot-prop-id network]} (install-layer-closure-cell n proc-id :base)
        arg-base-ids (vec (repeatedly (count arg-ids) ids/new-node-id))
        out-base-id (ids/new-node-id)
        n1 (reduce
            (fn [acc id] (if (contains? (net/net-env acc) id) acc (nb/install-cell acc id)))
            network
            (into [out-base-id] arg-base-ids))
        [arg-slot-prop-ids n2]
        (reduce
         (fn [[prop-ids acc] [arg-id arg-base-id]]
           (let [[p acc'] ((p:base arg-base-id arg-id) acc)]
             [(conj prop-ids p) acc']))
         [[] n1]
         (map vector arg-ids arg-base-ids))
        [compound-prop-id n3] ((prop/compound-propagator closure-id arg-base-ids [out-base-id]) n2)
        [out-slot-prop-id n4] ((p:base out-base-id out-id) n3)]
    [n4 (into layer-prop-ids
              (conj arg-slot-prop-ids slot-prop-id compound-prop-id out-slot-prop-id))]))

(defn- install-non-base-layer
  [n proc-id arg-ids out-id layer-name layer-prop-ids]
  (let [{:keys [closure-id slot-prop-id network]} (install-layer-closure-cell n proc-id layer-name)
        current-id (ids/new-node-id)
        result-id (ids/new-node-id)
        n1 (-> network
               (nb/install-cell current-id)
               (nb/install-cell result-id))
        [current-slot-prop-id n2] ((p:layer layer-name current-id out-id) n1)
        [compound-prop-id n3] ((prop/compound-propagator closure-id
                                                         (into [current-id] arg-ids)
                                                         [result-id])
                               n2)
        [result-slot-prop-id n4] ((p:layer layer-name result-id out-id) n3)]
    [n4 (conj layer-prop-ids
              slot-prop-id
              current-slot-prop-id
              compound-prop-id
              result-slot-prop-id)]))

(defn- install-layer-application
  [n proc-id arg-ids out-id layer-name arg-values prop-ids]
  (cond
    (= layer-name :base)
    (install-base-layer n proc-id arg-ids out-id prop-ids)

    (some #(layer-present? % layer-name) arg-values)
    (install-non-base-layer n proc-id arg-ids out-id layer-name prop-ids)

    :else
    [n prop-ids]))

(defn- build-application-net
  [outer-net proc-id arg-ids out-id layers arg-values]
  (let [out-entry (net/network-env-lookup outer-net out-id)
        out-strongest (cell/cell-strongest out-entry)
        n0 (-> net/empty-net
               (install-local-cell proc-id outer-net)
               (#(reduce (fn [acc id] (install-local-cell acc id outer-net)) % arg-ids))
               (nb/install-cell out-id
                                (normalize-layered-value out-strongest)
                                (normalize-layered-value out-strongest)))]
    (reduce
     (fn [[n prop-ids] layer-name]
       (install-layer-application n proc-id arg-ids out-id layer-name arg-values prop-ids))
     [n0 []]
     layers)))

(defn- layered-apply-activate
  [proc-id arg-ids out-id]
  (fn [_input-ids _output-ids outer-net]
    (let [proc-value (net/network-cell-strongest outer-net proc-id)
          arg-values (mapv #(net/network-cell-strongest outer-net %) arg-ids)]
      (if (or (value/unusable? proc-value)
              (apply value/any-unusable-values? arg-values))
        []
        (let [layers (available-branches proc-value)
              [app-net prop-ids] (build-application-net outer-net
                                                        proc-id
                                                        arg-ids
                                                        out-id
                                                        layers
                                                        arg-values)
              after (nb/run-propagators app-net prop-ids)]
          (diff/diff-cells [out-id] [out-id] after outer-net))))))

(defn p:apply-layered
  [proc-id arg-ids out-id]
  (prop/construct-propagator
   (layered-apply-activate proc-id (vec arg-ids) out-id)
   (into [proc-id] arg-ids)
   [out-id]))
