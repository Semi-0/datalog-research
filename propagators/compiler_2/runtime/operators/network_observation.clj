(ns propagators.compiler-2.runtime.operators.network-observation
  "Policy-free observation of one immutable network snapshot."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.operators.versioned-definition :as definition]
            [propagators.datastructures.compound-object :as object]
            [propagators.datastructures.compound-object.network-slot :as network-slot]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.experimental.visualization.data :as visualization-data]
            [propagators.graph :as graph]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.compiler-2.runtime.declaration-effects :as nb]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.snapshot :as semantic]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.relationship-observer :as relationships]
            [propagators.semantic-trace.facts :as facts]))

(defprotocol NetworkSnapshot
  (snapshot-neighbors [snapshot node-id])
  (snapshot-node-info [snapshot node-id]))

(defprotocol SemanticSnapshot
  (snapshot-semantic-graph [snapshot root]))

(defprotocol RelationshipSnapshot
  (snapshot-value [snapshot node])
  (snapshot-dataflow [snapshot graph])
  (snapshot-roots [snapshot roots])
  (snapshot-children [snapshot parent]))

(defn- captured-slot-values
  "Retain reachable public accessor cells without adding them to scene topology."
  [graph source-net]
  (loop [pending (seq (:values graph))
         captured (:values graph)
         seen (set (keys (:values graph)))
         missing []]
    (if (nil? pending)
      (cond-> (assoc graph :values captured)
        (seq missing)
        (assoc :semantic-capture/diagnostics
               (mapv (fn [id]
                       {:kind :missing
                        :message "A captured public slot is unavailable"
                        :cell id})
                     missing)))
      (let [[[node-key candidate] & more] pending
            path (if (and (vector? node-key) (vector? (first node-key)))
                   (first node-key)
                   [:outer])
            owner (relationships/network-at-path source-net path)
            candidate (visualization-data/payload candidate)]
        (if (object/accessor-network? candidate)
          (let [parent-keys
                (for [slot-key (object/accessor-slot-keys candidate)
                      parent-id (object/accessor-parent-ids candidate slot-key)]
                  [path parent-id])
                fresh (remove seen parent-keys)
                available
                (keep (fn [[_ id :as key]]
                        (when (and owner (contains? (net/net-env owner) id))
                          [key (net/network-cell-strongest owner id)]))
                      fresh)
                available-keys (set (map first available))]
            (recur (concat more available)
                   (into captured available)
                   (into seen fresh)
                   (into missing (remove available-keys fresh))))
          (recur more captured seen missing))))))

(defn- node-kind
  [entry]
  (cond
    (cell/cell? entry) :cell
    (prop/prop? entry) :propagator
    :else :unknown))

(defn- closure-declaration?
  [candidate]
  (closure-value/closure-info?
   (application/callable-declaration candidate)))

(defn- versioned-network-declaration?
  [candidate]
  (let [name (operator-value/operator-name
              (application/callable-declaration candidate))]
    (and (vector? name)
         (= :compiler-2/versioned-definition (first name)))))

(defn- closure-application?
  "True when a semantic propagator is an application of a Lain network.
  Primitive application and compiler-wiring children remain inside their
  owning semantic plane instead of becoming stack occurrences."
  [source-net node-key]
  (let [path (if (and (vector? node-key) (vector? (first node-key)))
               (first node-key)
               [:outer])
        owner (relationships/network-at-path source-net path)
        semantic-application (when owner
                               (some #(when (= node-key (:id %)) %)
                                     (facts/applications owner path)))
        operator-id (some-> semantic-application :operator second)
        callable (when (and owner operator-id
                            (contains? (net/net-env owner) operator-id))
                   (visualization-data/payload
                    (net/network-cell-strongest owner operator-id)))
        selected-calls
        (when (and owner semantic-application
                   (not (closure-declaration? callable)))
          (definition/active-call-topologies
           owner operator-id
           (mapv second (:arguments semantic-application))
           (some-> semantic-application :result second)))]
    (or (closure-declaration? callable)
        (versioned-network-declaration? callable)
        (and (= 1 (count selected-calls))
             (let [selected (first selected-calls)
                   selected-callable
                   (visualization-data/payload
                    (net/network-cell-strongest owner (:operator-id selected)))]
               (closure-declaration? selected-callable))))))

(deftype FrozenNetworkSnapshot [source-net observation]
  capture/SemanticCaptureSource
  (capture-fragment [_ request]
    (let [requested-observation (:observation request)
          observation-id (:observation/id observation)
          root (:root request)
          parent (:parent request)
          supplied-graph (:graph request)
          scope-id (or (:scope request)
                       (when parent [observation-id parent])
                       :root)]
      (cond
        (and requested-observation
             (not= requested-observation observation-id))
        (throw (ex-info "Capture request belongs to another observation"
                        {:requested requested-observation
                         :available observation-id}))

        (and (nil? root) (nil? parent) (nil? supplied-graph))
        (throw (ex-info "Semantic capture request requires a graph, root, or parent" {}))

        :else
        (let [graph (-> (cond
                          supplied-graph supplied-graph
                          parent (dataflow/child-semantic-network-graph source-net parent)
                          :else (semantic/root-graph source-net root observation))
                        (captured-slot-values source-net))
              nested (into {}
                           (keep (fn [[node-id kind]]
                                   (when (and (= :propagator kind)
                                              (closure-application? source-net node-id)
                                              (seq (relationship/children
                                                    (net/net-relationship source-net)
                                                    node-id)))
                                     [node-id [observation-id node-id]])))
                           (:node-kinds graph))
              graph (assoc graph :semantic-capture/nested-scopes nested)]
          (capture/fragment
           {:observation observation-id
            :scopes {scope-id graph}
            :support (apply support/combine
                            (or (:support request) #{})
                            (map datum/support-of (vals (:values graph))))
            :provenance [{:capture/refinement (or (:refinement request) :initial)
                          :capture/scope scope-id}]})))))
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
          :else value/nothing)}))
  SemanticSnapshot
  (snapshot-semantic-graph [_ root]
    (captured-slot-values (semantic/root-graph source-net root observation) source-net))
  RelationshipSnapshot
  (snapshot-value [_ node]
    ((requiring-resolve 'propagators.experimental.visualization.data/payload)
     (net/network-cell-strongest source-net node)))
  (snapshot-dataflow [_ graph]
    (captured-slot-values
     (dataflow/dataflow-graph source-net (capture/root-graph graph)) source-net))
  (snapshot-roots [_ roots]
    (let [output (:output observation)
          origins (facts/root-seeds source-net roots)
          selected ((relationships/connected-root-node-keys origins #{output}) source-net)]
      (-> (relationships/snapshot source-net selected)
          (assoc :dataflow/seeds (set (map #(vector [:outer] %) origins))
                 :dataflow/observer-outputs #{[[:outer] output]})
          (captured-slot-values source-net))))
  (snapshot-children [_ parent]
    (captured-slot-values (dataflow/child-semantic-network-graph source-net parent) source-net)))

(defn frozen-snapshot
  ([network] (frozen-snapshot network {}))
  ([network observation]
   (FrozenNetworkSnapshot.
    network
    (assoc observation :observation/id
           (or (:observation/id observation) (random-uuid))))))

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
    {:net (nb/register-props installed [prop-id])
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
  (let [{compiled :net} (build (nb/begin network))]
    (nb/result compiled)))

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
