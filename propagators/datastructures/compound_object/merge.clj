(ns propagators.datastructures.compound-object.merge
  "Merge-owned accessor-network representation and deterministic topology."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as core]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.datastructures.named-network :as named]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def accessor-network-key
  (core/internal-metadata-key :accessor-network))

(def source-slots-key
  (core/internal-metadata-key :source-slots))

(def accessor-sync-key
  (core/internal-metadata-key :accessor-sync))

(defn accessor-network?
  [x]
  (and (net/net? x)
       (true? (net/network-dict-entry x accessor-network-key))))

(defn empty-accessor-network
  []
  (net/net-with-dict
   net/empty-net
   {accessor-network-key true
    core/slot-index-key {}
    core/read-only-slots-key #{}
    source-slots-key {}}))

(defn accessor-declaration
  [slot-key parent-id]
  (-> (empty-accessor-network)
      (net/update-net-dict-entry
       core/slot-index-key
       #(update (or % {}) slot-key (fnil conj #{}) parent-id))))

(defn source-slots
  [n]
  (or (net/network-dict-entry n source-slots-key) {}))

(defn- network-cell-entry
  [n id]
  (when (ids/node-id? id)
    (get (net/net-env n) id)))

(defn- slot-cell-id
  [n slot-key]
  (let [id (net/network-dict-entry n slot-key)]
    (when (cell/cell? (network-cell-entry n id))
      id)))

(defn- public-slot-present?
  [n slot-key]
  (some? (slot-cell-id n slot-key)))

(defn source-slot-present?
  [n slot-key]
  (or (contains? (source-slots n) slot-key)
      (public-slot-present? n slot-key)))

(defn source-slot-value
  [n slot-key]
  (if (contains? (source-slots n) slot-key)
    (get (source-slots n) slot-key)
    (if-let [id (slot-cell-id n slot-key)]
      (net/network-cell-strongest n id)
      value/nothing)))

(defn- ensure-accessor-metadata
  [n]
  (-> n
      (net/assoc-net-dict-entry accessor-network-key true)
      (net/update-net-dict-entry core/slot-index-key #(or % {}))
      (net/update-net-dict-entry core/read-only-slots-key #(or % #{}))
      (net/update-net-dict-entry source-slots-key #(or % {}))))

(defn- with-source-slots
  ([source-slots]
   (with-source-slots source-slots #{}))
  ([source-slots read-only-slots]
   (ensure-accessor-metadata
    (net/net-with-dict
     net/empty-net
     {core/read-only-slots-key read-only-slots
      source-slots-key source-slots}))))

(defn- vector-source-slots
  [v]
  (assoc (into {} (map-indexed vector v)) :count (count v)))

(defn as-accessor-network
  "Normalize collection content into the experimental accessor topology value."
  [x]
  (cond
    (value/contradiction? x) value/contradiction
    (accessor-network? x) (ensure-accessor-metadata x)
    (value/nothing? x) (empty-accessor-network)

    (named/named-network? x)
    (ensure-accessor-metadata x)

    (net/net? x)
    value/contradiction

    (vector? x)
    (with-source-slots (vector-source-slots x) #{:count})

    (map? x)
    (with-source-slots (into {} x))

    :else value/contradiction))

(defn- stable-node-id
  [parts]
  (ids/->NodeId
   (java.util.UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:compound-object/accessor] parts)) "UTF-8"))))

(defn- canonical-cell-key
  [slot-key]
  (core/internal-metadata-key :accessor slot-key :canonical-cell))

(defn- canonical-cell-id
  [slot-key]
  (stable-node-id [:canonical slot-key]))

(defn- ensure-canonical-cell
  [n slot-key]
  (let [id (canonical-cell-id slot-key)]
    (-> (cond-> n
          (not (contains? (net/net-env n) id)) (nb/install-cell id))
        (net/assoc-net-dict-entry (canonical-cell-key slot-key) id))))

(defn accessor-parent-ids
  [n slot-key]
  (net/network-indexed-ids n core/slot-index-key slot-key))

(defn accessor-slot-keys
  "Slot keys represented by source values or declared accessor routes."
  [n]
  (let [slot-index (or (net/network-dict-entry n core/slot-index-key) {})]
    (set (concat (keys (source-slots n))
                 (keys slot-index)
                 (filter #(public-slot-present? n %)
                         (core/public-slot-keys n))))))

(defn accessor-declarations-for
  [n collection-id]
  (let [v (if (contains? (net/net-env n) collection-id)
            (net/network-cell-strongest n collection-id)
            value/nothing)]
    (if (accessor-network? v)
      (into {}
            (keep (fn [slot-key]
                    (let [parent-ids (accessor-parent-ids v slot-key)]
                      (when (seq parent-ids)
                        [slot-key
                         (zipmap parent-ids
                                 (repeat {:strategy :network-slot}))]))))
            (accessor-slot-keys v))
      {})))

(defn- avatar-key
  [slot-key parent-id]
  (core/internal-metadata-key :accessor slot-key :avatar parent-id))

(defn- avatar-cell-id
  [slot-key parent-id]
  (stable-node-id [:avatar slot-key parent-id]))

(defn accessor-avatar-id
  [slot-key parent-id]
  (avatar-cell-id slot-key parent-id))

(defn- sync-marker-key
  [slot-key parent-id direction]
  (into accessor-sync-key [slot-key parent-id direction]))

(defn- sync-prop-id
  [slot-key parent-id direction]
  (stable-node-id [:sync slot-key parent-id direction]))

(defn- content-copy-installed?
  [n prop-id from-id to-id]
  (let [g (net/net-graph n)
        from-node (get g from-id)
        prop-node (get g prop-id)]
    (and (contains? (net/net-env n) prop-id)
         from-node
         prop-node
         (contains? (:outputs from-node) prop-id)
         (contains? (:inputs prop-node) from-id)
         (contains? (:outputs prop-node) to-id))))

(defn- install-content-copy
  [n prop-id from-id to-id]
  (if (content-copy-installed? n prop-id from-id to-id)
    n
    (second
     ((prop/construct-propagator
       prop-id
       (fn [_inputs _outputs network]
         (let [entry (net/network-env-lookup network from-id)
               content (cell/cell-content entry)]
           [(message to-id
                     (if (evidence/evidence-set? content)
                       (cell/cell-strongest entry)
                       content))]))
       [from-id]
       [to-id])
      n))))

(defn- ensure-accessor-bi-sync
  [n slot-key parent-id parent-avatar-id canonical-avatar-id]
  (let [from-key (sync-marker-key slot-key parent-id :from->canonical)
        to-key (sync-marker-key slot-key parent-id :canonical->from)
        from-prop-id (sync-prop-id slot-key parent-id :from->canonical)
        to-prop-id (sync-prop-id slot-key parent-id :canonical->from)]
    ;; ponytail: markers are the idempotence contract; graph rechecks are the hot path.
    (if (and (net/network-dict-entry n from-key)
             (net/network-dict-entry n to-key))
      n
      (-> n
          (install-content-copy from-prop-id parent-avatar-id canonical-avatar-id)
          (install-content-copy to-prop-id canonical-avatar-id parent-avatar-id)
          (net/assoc-net-dict-entry from-key #{:installed})
          (net/assoc-net-dict-entry to-key #{:installed})))))

(defn ensure-accessor-avatar
  [n slot-key parent-id]
  (let [id (avatar-cell-id slot-key parent-id)]
    (-> (cond-> n
          (not (contains? (net/net-env n) id)) (nb/install-cell id))
        (net/assoc-net-dict-entry (avatar-key slot-key parent-id) id)
        (net/update-net-dict-entry core/slot-index-key
                                   #(update (or % {})
                                            slot-key
                                            (fnil conj #{})
                                            parent-id)))))

(defn ensure-accessor-route
  [collection-net slot-key parent-id]
  (let [n0 (ensure-accessor-avatar collection-net slot-key parent-id)
        n1 (ensure-canonical-cell n0 slot-key)
        canonical-avatar-id (canonical-cell-id slot-key)]
    (reduce
     (fn [n parent-id*]
       (let [n* (ensure-accessor-avatar n slot-key parent-id*)
             parent-avatar-id (accessor-avatar-id slot-key parent-id*)]
         (ensure-accessor-bi-sync n*
                                  slot-key
                                  parent-id*
                                  parent-avatar-id
                                  canonical-avatar-id)))
     n1
     (accessor-parent-ids n1 slot-key))))

(defn- ensure-accessor-slot-topology
  [collection-net slot-key parent-ids]
  (let [n0 (ensure-canonical-cell collection-net slot-key)
        canonical-avatar-id (canonical-cell-id slot-key)]
    (reduce
     (fn [n parent-id]
       (let [n* (ensure-accessor-avatar n slot-key parent-id)
             parent-avatar-id (accessor-avatar-id slot-key parent-id)]
         (ensure-accessor-bi-sync n*
                                  slot-key
                                  parent-id
                                  parent-avatar-id
                                  canonical-avatar-id)))
     n0
     parent-ids)))

(defn refine-accessor-network
  [collection-net]
  (let [slot-index (or (net/network-dict-entry collection-net core/slot-index-key) {})]
    (reduce-kv
     (fn [n slot-key parent-ids]
       (ensure-accessor-slot-topology n slot-key parent-ids))
     (as-accessor-network collection-net)
     slot-index)))

(defn attach-network-slot-sync
  [collection-net slot-key parent-id _parent-net]
  (ensure-accessor-route (as-accessor-network collection-net) slot-key parent-id))

(defn register-accessor-parent
  "Record `parent-id` as a participant in `slot-key` on an accessor network.

  The participant may be a local node id or a scoped dispatch address. Scoped
  addresses are kept as slot fanout targets; they are not required to be cells in
  the currently executing parent network.
  "
  [collection-net slot-key parent-id]
  (ensure-accessor-route (as-accessor-network collection-net) slot-key parent-id))

(defn- named-network-content
  [content]
  (if (evidence/evidence-set? content)
    (evidence/strongest content)
    content))

(defn- behavior-value?
  [v]
  (boolean
   (when-let [behavior-value? (requiring-resolve
                               'propagators.datastructures.behavior/behavior-value?)]
     (behavior-value? v))))

(defn- merge-behavior
  [a b]
  ((requiring-resolve 'propagators.datastructures.behavior/merge-content) a b))

(declare merge-named-network-content)

(defn- merge-source-slot-value
  [a b]
  (cond
    (nil? a) b
    (nil? b) a
    (= a b) a
    (and (behavior-value? a) (behavior-value? b)) (merge-behavior a b)
    (and (accessor-network? a) (accessor-network? b)) (merge-named-network-content a b)
    (and (named/named-network? a) (named/named-network? b)) (named/join a b)
    :else b))

(defn- merge-source-slots
  [a b]
  (merge-with merge-source-slot-value
              (source-slots a)
              (source-slots b)))

(defn- without-source-slots
  [n]
  (net/net-with-dict n (dissoc (net/net-dict-or-empty n) source-slots-key)))

(defn merge-named-network-content
  [content update]
  (let [content* (named-network-content
                  (if (evidence/evidence-set? content)
                    content
                    (as-accessor-network content)))
        source-slots* (merge-source-slots content* update)
        joined (cond
                 (value/contradiction? content*) value/contradiction
                 (value/contradiction? update) value/contradiction
                 (value/nothing? content*) update
                 (value/nothing? update) content*
                 (and (named/named-network? content*)
                      (named/named-network? update)) (named/join
                                                       (without-source-slots content*)
                                                       (without-source-slots update))
                 :else value/contradiction)]
    (if (value/contradiction? joined)
      value/contradiction
      (refine-accessor-network
       (net/assoc-net-dict-entry joined source-slots-key source-slots*)))))
