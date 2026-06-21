(ns propagators.datastructures.compound-object.network-slot
  "Demand-driven compound-object slots backed by a structural inner network."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as core]
            [propagators.datastructures.named-network :as named]
            [propagators.effectful-execution :as effect]
            [propagators.effectful-sync :as sync]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.scoped-address :as scoped]))

;; ponytail: this file couples source slots, canonical fanout, scoped addresses,
;; and projection messages. Split only when those responsibilities stabilize.

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

(defn- canonical-key
  [slot-key]
  (core/internal-metadata-key :accessor slot-key :canonical))

(defn- canonical-parent-id
  [n slot-key]
  (:parent (net/network-dict-entry n (canonical-key slot-key))))

(defn- assoc-canonical-parent
  [n slot-key parent-id]
  (net/assoc-net-dict-entry n (canonical-key slot-key) {:parent parent-id}))

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

(defn- sync-marker-key
  [slot-key parent-id canonical-id direction]
  [accessor-sync-key slot-key parent-id canonical-id direction])

(defn- attach-marked-bi-sync
  [n slot-key parent-id parent-avatar-id canonical-id canonical-avatar-id]
  (let [from-key (sync-marker-key slot-key parent-id canonical-id :from->canonical)
        to-key (sync-marker-key slot-key parent-id canonical-id :canonical->from)
        dict (net/net-dict-or-empty n)]
    (if (and (contains? dict from-key)
             (contains? dict to-key))
      n
      (let [n' (sync/attach-content-bi-sync n
                                            parent-avatar-id
                                            canonical-avatar-id
                                            from-key
                                            to-key)]
        (-> n'
            (net/assoc-net-dict-entry from-key #{:installed})
            (net/assoc-net-dict-entry to-key #{:installed}))))))

(defn- ensure-accessor-avatar
  [n slot-key parent-id]
  (sync/ensure-indexed-shell-avatar n core/slot-index-key slot-key parent-id))

(defn- ensure-accessor-route
  [collection-net slot-key parent-id]
  (let [n0 (ensure-accessor-avatar collection-net slot-key parent-id)
        canonical-id (or (canonical-parent-id n0 slot-key) parent-id)
        n1 (if (canonical-parent-id n0 slot-key)
             n0
             (assoc-canonical-parent n0 slot-key canonical-id))
        n2 (ensure-accessor-avatar n1 slot-key canonical-id)
        canonical-avatar-id (get (net/net-dict-or-empty n2) canonical-id)]
    (reduce
     (fn [n parent-id*]
       (if (= parent-id* canonical-id)
         n
         (let [n* (ensure-accessor-avatar n slot-key parent-id*)
               parent-avatar-id (get (net/net-dict-or-empty n*) parent-id*)]
           (attach-marked-bi-sync n*
                                  slot-key
                                  parent-id*
                                  parent-avatar-id
                                  canonical-id
                                  canonical-avatar-id))))
     n2
     (accessor-parent-ids n2 slot-key))))

(defn refine-accessor-network
  [collection-net]
  (let [slot-index (or (net/network-dict-entry collection-net core/slot-index-key) {})]
    (reduce-kv
     (fn [n slot-key parent-ids]
       (reduce #(ensure-accessor-route %1 slot-key %2) n parent-ids))
     (as-accessor-network collection-net)
     slot-index)))

(defn- seed-accessor-avatars
  [stable-net slot-key parent-net parent-ids]
  (reduce
   (fn [n parent-id]
     (if (contains? (net/net-env parent-net) parent-id)
       (sync/ensure-parent-avatar n core/slot-index-key slot-key parent-id parent-net)
       n))
   stable-net
   parent-ids))

(defn- accessor-seed-ids
  [subnet parent-ids]
  (let [dict (net/net-dict-or-empty subnet)]
    (vec (keep #(get dict %) parent-ids))))

(defn- network-cell-present?
  [n id]
  (and (contains? (net/net-env n) id)
       (contains? (net/net-graph n) id)))

(defn- dispatch-address-present?
  [n id]
  (and (scoped/address? id)
       (contains? (net/net-dict-or-empty n) id)))

(defn- messageable-parent?
  [parent-net parent-id]
  (or (network-cell-present? parent-net parent-id)
      (dispatch-address-present? parent-net parent-id)))

(defn- run-accessor-inner-net
  [stable-net slot-key parent-net seed-parent-ids]
  (let [exec-net (seed-accessor-avatars stable-net
                                        slot-key
                                        parent-net
                                        seed-parent-ids)
        parent-ids (accessor-parent-ids stable-net slot-key)
        [_ after _updated*] (effect/execute-subnet
                             exec-net
                             (fn [subnet _updated*] subnet)
                             #(accessor-seed-ids % seed-parent-ids))]
    {:executed-net after
     :parent-ids parent-ids}))

(defn- equivalent-to-parent?
  [parent-net parent-id v]
  (and (contains? (net/net-env parent-net) parent-id)
       (sync/strongest-equivalent?
        v
        (net/network-cell-strongest parent-net parent-id)
        parent-net)))

(defn- source-slot-messages
  [collection-net slot-key parent-net]
  (let [v (source-slot-value collection-net slot-key)]
    (if (or (not (source-slot-present? collection-net slot-key))
            (value/unusable? v))
      []
      (->> (accessor-parent-ids collection-net slot-key)
           (filter #(messageable-parent? parent-net %))
           (remove #(equivalent-to-parent? parent-net % v))
           (mapv #(message % v))))))

(defn- projected-accessor-messages
  [executed-net parent-ids parent-net]
  (let [dict (net/net-dict-or-empty executed-net)]
    (->> parent-ids
         (keep (fn [parent-id]
                 (when-let [avatar-id (and (messageable-parent? parent-net parent-id)
                                           (get dict parent-id))]
                   (let [v (net/network-cell-strongest executed-net avatar-id)]
                     (when-not (or (value/unusable? v)
                                   (equivalent-to-parent? parent-net parent-id v))
                       (message parent-id v))))))
         vec)))

(defn- accessor-synced?
  [collection-net slot-key parent-net]
  (let [parent-ids* (accessor-parent-ids collection-net slot-key)
        dispatch-ids (filter #(dispatch-address-present? parent-net %) parent-ids*)
        parent-ids (filter #(network-cell-present? parent-net %) parent-ids*)]
    (and (empty? dispatch-ids)
         (or (empty? parent-ids)
             (let [baseline (net/network-cell-strongest parent-net (first parent-ids))]
               (every? #(sync/strongest-equivalent?
                         baseline
                         (net/network-cell-strongest parent-net %)
                         parent-net)
                       (rest parent-ids)))))))

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

(defn- network-slot-messages
  [collection-id slot-key parent-id parent-net collection-net]
  (let [known-parent? (contains? (accessor-parent-ids collection-net slot-key)
                                 parent-id)]
    (if-not known-parent?
      [(message collection-id (accessor-declaration slot-key parent-id))]
      (let [stable-net (refine-accessor-network collection-net)
            source-messages (source-slot-messages stable-net slot-key parent-net)]
        (if (and (empty? source-messages)
                 (accessor-synced? stable-net slot-key parent-net))
          []
          (let [{:keys [executed-net parent-ids]}
                (run-accessor-inner-net stable-net
                                        slot-key
                                        parent-net
                                        [parent-id])]
            (into source-messages
                  (projected-accessor-messages executed-net
                                               parent-ids
                                               parent-net))))))))

(defn network-slot-activation
  [slot-key parent-id collection-id]
  (fn [_inputs _outputs parent-net]
    (let [collection-net (-> parent-net
                             (net/network-cell-strongest collection-id)
                             as-accessor-network)]
      (if (value/contradiction? collection-net)
        [(message collection-id value/contradiction)]
        (network-slot-messages collection-id
                               slot-key
                               parent-id
                               parent-net
                               collection-net)))))

(defn p:network-slot
  [slot-key parent-id collection-id]
  (let [prop-id (ids/new-node-id)
        activate (network-slot-activation slot-key parent-id collection-id)]
    (fn [network]
      ((prop/construct-propagator
        prop-id
        activate
        [parent-id collection-id]
        [parent-id collection-id])
       network))))

(defn p:network-car [elem-id collection-id]
  (p:network-slot :car elem-id collection-id))

(defn p:network-cdr [elem-id collection-id]
  (p:network-slot :cdr elem-id collection-id))

(defn p:network-cons
  [head-id tail-id collection-id]
  (fn [network]
    (let [[car-prop n] (nb/install-propagator network
                                              (p:network-car head-id collection-id))
          [cdr-prop n] (nb/install-propagator n
                                              (p:network-cdr tail-id collection-id))]
      [[car-prop cdr-prop] n])))
