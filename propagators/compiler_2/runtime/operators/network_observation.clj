(ns propagators.compiler-2.runtime.operators.network-observation
  "Policy-free observation of one immutable network snapshot."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.runtime.topology-effects :as topology]
            [propagators.datastructures.compound-object.network-slot :as network-slot]
            [propagators.graph :as graph]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defprotocol NetworkSnapshot
  (snapshot-neighbors [snapshot node-id])
  (snapshot-node-info [snapshot node-id]))

(defn- node-kind
  [entry]
  (cond
    (cell/cell? entry) :cell
    (prop/prop? entry) :propagator
    :else :unknown))

(deftype FrozenNetworkSnapshot [source-net]
  NetworkSnapshot
  (snapshot-neighbors [_ node-id]
    (let [node (graph/get-node (net/net-graph source-net) node-id)]
      {:node/id node-id
       :node/inputs (vec (sort-by pr-str (graph/node-input-ids node)))
       :node/outputs (vec (sort-by pr-str (graph/node-output-ids node)))}))
  (snapshot-node-info [_ node-id]
    (let [entry (net/network-env-lookup source-net node-id)
          kind (node-kind entry)]
      {:node/id node-id
       :node/kind kind
       :node/name
       (cond
         (= kind :propagator) (prop/prop-name entry)
         (= kind :cell) (get (net/net-dict-or-empty source-net)
                             [:node/name node-id]
                             value/nothing)
         :else value/nothing)})))

(defn frozen-snapshot
  [network]
  (FrozenNetworkSnapshot. network))

(defn frozen-snapshot?
  [candidate]
  (satisfies? NetworkSnapshot candidate))

(defn- ensure-seeded
  [network id candidate]
  (if (contains? (net/net-env network) id)
    network
    (nb/install-cell network id candidate candidate)))

(defn- stable-slot
  [scope slot-key value-id collection-id]
  (let [prop-id (basis/stable-node-id
                 :compiler-2 :network-observation
                 scope :slot slot-key)]
    (fn [network]
      ((prop/construct-propagator
        prop-id
        [:compiler-2/network-observation slot-key]
        (network-slot/network-slot-activation
         slot-key value-id collection-id)
        [value-id collection-id]
        [value-id collection-id])
       (reduce nb/ensure-cell network [value-id collection-id])))))

(defn- install-one
  [{:keys [net props]} installer]
  (let [[prop-id installed] (installer net)]
    {:net installed
     :props (conj props prop-id)}))

(defn- value-slot
  [slot-key value]
  {:slot/key slot-key
   :slot/value value})

(defn- cell-slot
  [slot-key cell-id]
  {:slot/key slot-key
   :slot/cell cell-id})

(defn- install-slots
  [network scope collection-id slots]
  (reduce
   (fn [state {:slot/keys [key value cell]}]
     (let [value-id
           (or cell
               (basis/stable-node-id
                :compiler-2 :network-observation
                scope :value key))

           seeded
           (cond
             cell
             (update state :net nb/ensure-cell value-id)

             :else
             (update state :net ensure-seeded value-id value))]
       (install-one
        seeded
        (stable-slot scope key value-id collection-id))))
   {:net (nb/ensure-cell network collection-id)
    :props []}
   slots))

(defn- install-list
  [network scope values root-id]
  (let [values (vec values)]
    (if (empty? values)
      {:net (ensure-seeded network root-id basis/list-empty-marker)
       :props []}
      (let [terminal-id
            (basis/stable-node-id
             :compiler-2 :network-observation scope :terminal)

            collection-ids
            (into [root-id]
                  (mapv
                   (fn [position]
                     (basis/stable-node-id
                      :compiler-2 :network-observation
                      scope :list position))
                   (range 1 (count values))))

            tail-ids
            (conj (vec (rest collection-ids)) terminal-id)

            prepared
            (ensure-seeded network terminal-id basis/list-empty-marker)]
        (reduce
         (fn [state [position candidate collection-id tail-id]]
           (let [head-id
                 (basis/stable-node-id
                  :compiler-2 :network-observation
                  scope :head position)

                 seeded
                 (update state :net ensure-seeded head-id candidate)

                 with-car
                 (install-one
                  seeded
                  (stable-slot
                   [scope :list position]
                   :car
                   head-id
                   collection-id))]
             (install-one
              with-car
              (stable-slot
               [scope :list position]
               :cdr
               tail-id
               collection-id))))
         {:net prepared :props []}
         (map vector
              (range)
              values
              collection-ids
              tail-ids))))))

(defn- neighborhood-topology
  [network out-id {:keys [node/id node/inputs node/outputs]}]
  (let [scope [:neighbors out-id]
        inputs-id
        (basis/stable-node-id
         :compiler-2 :network-observation scope :inputs)

        outputs-id
        (basis/stable-node-id
         :compiler-2 :network-observation scope :outputs)

        inputs-topology
        (install-list network [scope :inputs] inputs inputs-id)

        outputs-topology
        (install-list
         (:net inputs-topology)
         [scope :outputs]
         outputs
         outputs-id)

        record-topology
        (install-slots
         (:net outputs-topology)
         scope
         out-id
         [(value-slot :node/id id)
          (cell-slot :node/inputs inputs-id)
          (cell-slot :node/outputs outputs-id)])]
    {:net (:net record-topology)
     :props
     (into
      (into (:props inputs-topology)
            (:props outputs-topology))
      (:props record-topology))}))

(defn- node-info-topology
  [network out-id info]
  (install-slots
   network
   [:node-info out-id]
   out-id
   (mapv (fn [[slot-key value]]
           (value-slot slot-key value))
         (sort-by (comp pr-str first) info))))

(defn- observation-effects
  [network out-id build]
  (let [{compiled :net props :props} (build network)]
    (topology/network-diff network compiled props)))

(defn- observed-value
  [network id]
  (if (contains? (net/net-env network) id)
    (net/network-cell-strongest network id)
    value/nothing))

(defn- observation-messages
  [network snapshot-id node-id out-id build]
  (let [snapshot (observed-value network snapshot-id)
        node (observed-value network node-id)]
    (cond
      (or (value/unusable? snapshot)
          (value/unusable? node))
      []

      (not (frozen-snapshot? snapshot))
      [(message out-id value/contradiction)]

      :else
      (try
        (observation-effects
         network
         out-id
         #(build % out-id snapshot node))
        (catch clojure.lang.ExceptionInfo _
          [(message out-id value/contradiction)])))))

(defn- observation-operator
  [name build]
  (operator-value/propagator-operator
   {:name name
    :output-selector
    (fn [arg-ids fallback-id]
      (case (count arg-ids)
        2 [fallback-id]
        3 [(nth (vec arg-ids) 2)]
        (throw
         (ex-info
          (str name " expects snapshot, node, and optional output")
          {:arg-ids (vec arg-ids)}))))
    :input-selector
    (fn [arg-ids _fallback-id _context-id]
      (case (count arg-ids)
        2 (vec arg-ids)
        3 (subvec (vec arg-ids) 0 2)
        (throw
         (ex-info
          (str name " expects snapshot, node, and optional output")
          {:arg-ids (vec arg-ids)}))))
    :activate
    (fn [network inputs outputs _context-id]
      (let [[snapshot-id node-id] inputs
            [out-id] outputs]
        (observation-messages
         network snapshot-id node-id out-id build)))}))

(defn neighbors-operator
  []
  (observation-operator
   'neighbors
   (fn [network out-id snapshot node]
     (neighborhood-topology
      network out-id
      (snapshot-neighbors snapshot node)))))

(defn node-info-operator
  []
  (observation-operator
   'node-info
   (fn [network out-id snapshot node]
     (node-info-topology
      network out-id
      (snapshot-node-info snapshot node)))))

(defn observation-environment
  []
  [['neighbors (neighbors-operator)]
   ['node-info (node-info-operator)]])
