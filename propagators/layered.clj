(ns propagators.layered
  "Layered data/procedure support built from compound-object slots."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value]
            [propagators.datastructures.named-network :as named]
            [propagators.ids :as ids]
            [propagators.layered.runtime :as runtime]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.stdlib.prop :as stdlib-prop])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(defn p:layer
  "Bidirectional sync between a layer value cell and a layered object slot."
  [layer-name layer-value-id layered-object-id]
  (obj/p:slot layer-name layer-value-id layered-object-id))

(defn p:base
  [base-value-id layered-object-id]
  (p:layer :base base-value-id layered-object-id))

(defn layer-addressable?
  "True when `v` declares `layer-name` as compound topology."
  [v layer-name]
  (and (named/named-network? v)
       (contains? (obj/accessor-slot-keys v) layer-name)))

(defn layer-parent-id
  "Return an already-declared outer cell for one live accessor layer."
  [network object-id layer-name]
  (->> (get (obj/accessor-declarations-for network object-id) layer-name)
       keys
       (filter #(contains? (net/net-env network) %))
       (sort-by pr-str)
       first))

(defn- stable-layer-id
  [& parts]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:layered/transport] parts))
               StandardCharsets/UTF_8))))

(defn- add-installer
  [{:keys [net props]} installer]
  (let [[prop-id installed] (installer net)]
    {:net installed :props (conj props prop-id)}))

(defn- add-layer-port
  [declared object-id layer-name port-id direction]
  (let [object-value (net/network-cell-strongest (:net declared) object-id)
        layered? (or (value/nothing? object-value)
                     (named/named-network? object-value))]
    (if layered?
      (add-installer declared (p:layer layer-name port-id object-id))
      (if (= :base layer-name)
        (case direction
          :source (add-installer declared (stdlib-prop/id object-id port-id))
          :target (add-installer declared (stdlib-prop/id port-id object-id))
          :both (-> declared
                    (add-installer (stdlib-prop/id object-id port-id))
                    (add-installer (stdlib-prop/id port-id object-id))))
        declared))))

(defn declare-forward-layer
  "Declare one directional link between corresponding object layers.

  Scalar sources remain scalar cells. Compound/accessor values are addressed
  through live slot topology and are never materialized."
  [network declaration-key layer-name from-id to-id]
  (let [from-layer-id (stable-layer-id declaration-key layer-name :from)
        to-layer-id (stable-layer-id declaration-key layer-name :to)
        declared {:net (-> network
                           (nb/ensure-cell from-layer-id)
                           (nb/ensure-cell to-layer-id))
                  :props []}]
    (-> declared
        (add-layer-port from-id layer-name from-layer-id :source)
        (add-layer-port to-id layer-name to-layer-id :target)
        (add-installer (stdlib-prop/id from-layer-id to-layer-id)))))

(defn declare-layer-reader
  "Declare a live layer projection into `out-id`.

  Raw scalar cells are already base-layer cells; compound values use slot
  topology. No value is copied into a host snapshot."
  [network declaration-key layer-name object-id out-id]
  (add-layer-port
   {:net (nb/ensure-cell network out-id) :props []}
   object-id
   layer-name
   out-id
   :source))

(defn declare-bidirectional-layer
  "Declare a shared live layer between two objects without snapshotting them."
  [network declaration-key layer-name left-id right-id]
  (let [layer-id (stable-layer-id declaration-key layer-name :shared)
        declared {:net (nb/ensure-cell network layer-id) :props []}]
    (-> declared
        (add-layer-port left-id layer-name layer-id :both)
        (add-layer-port right-id layer-name layer-id :both))))

(defn declare-forward-layer-chain
  [network declaration-key layer-name ids]
  (reduce (fn [{:keys [net props]} [index [from-id to-id]]]
            (let [declared (declare-forward-layer net
                                                  [declaration-key index]
                                                  layer-name
                                                  from-id
                                                  to-id)]
              {:net (:net declared)
               :props (into props (:props declared))}))
          {:net network :props []}
          (map-indexed vector (partition 2 1 ids))))

(defn declare-bidirectional-layer-chain
  [network declaration-key layer-name ids]
  (reduce (fn [{:keys [net props]} [index [left-id right-id]]]
            (let [declared (declare-bidirectional-layer
                            net [declaration-key index] layer-name left-id right-id)]
              {:net (:net declared)
               :props (into props (:props declared))}))
          {:net network :props []}
          (map-indexed vector (partition 2 1 ids))))

(defn transport-value
  "Move one complete information value through a layered boundary.

  Scope envelopes and accessor networks are opaque here. Their own cells and
  layered procedures retain authority for interpretation and selection."
  [source]
  source)

(defn forward-transport-messages
  "One-way layered transport. `read-update` owns content/history policy."
  [read-update network from-id to-id]
  (let [source (read-update network from-id)]
    (if (nil? source)
      []
      [(message to-id (transport-value source))])))

(defn bidirectional-transport-messages
  "Bidirectional layered transport without materializing either accessor."
  [read-update network left-id right-id]
  (into (forward-transport-messages read-update network left-id right-id)
        (forward-transport-messages read-update network right-id left-id)))

(defn p:layered-procedure
  "Attach one closure cell as a layer on a layered procedure cell."
  [layer-name closure-id proc-id]
  (p:layer layer-name closure-id proc-id))

(defn install-layered-procedure!
  "Declare a reactive procedure layer.

  This is topology only: it ensures the procedure and closure cells exist,
  installs the slot propagator, and runs it once so its declaration is merged
  into the procedure cell. It does not seed layer values."
  [n proc-id layer-name closure-id]
  (let [n0 (-> n
               (nb/ensure-cell proc-id)
               (nb/ensure-cell closure-id))
        [prop-id n1] ((p:layered-procedure layer-name closure-id proc-id) n0)]
    {:net (runner/completed-network (runner/run-network [prop-id] n1))
     :prop prop-id
     :closure closure-id}))

(declare p:apply-layered)

(defn p:layered-operator
  "Create a propagator installer backed by a layered-procedure cell."
  ([layered-procedure-id]
   (p:layered-operator :layered/apply layered-procedure-id))
  ([name layered-procedure-id]
   (fn [& node-ids]
     (let [nodes (vec node-ids)
           args (vec (butlast nodes))
           out (last nodes)]
       (p:apply-layered name layered-procedure-id args out)))))

(defn p:apply-layered
  ([proc-id arg-ids out-id]
   (p:apply-layered :layered/apply proc-id arg-ids out-id))
  ([name proc-id arg-ids out-id]
   (prop/construct-propagator
    name
    (runtime/activation proc-id (vec arg-ids) out-id)
    (into [proc-id] arg-ids)
    [out-id])))
