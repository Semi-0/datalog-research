(ns propagators.datastructures.compound-object.network-slot
  "Demand-driven compound-object slots backed by a structural inner network."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.merge :as compound-merge]
            [propagators.effectful-execution :as effect]
            [propagators.effectful-sync :as sync]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.scoped-address :as scoped]))

(defn- seed-accessor-avatars
  [stable-net slot-key parent-net parent-ids]
  (reduce
   (fn [n parent-id]
     (if-not (contains? (net/net-env parent-net) parent-id)
       n
       (let [n0 (compound-merge/ensure-accessor-avatar n slot-key parent-id)
             avatar-id (compound-merge/accessor-avatar-id slot-key parent-id)
             avatar (get (net/net-env n0) avatar-id)
             parent (net/network-env-lookup parent-net parent-id)
             merge-cell-entry (requiring-resolve
                               'propagators.cells.merge/merge-cell-entry)]
         (net/assoc-net-cell
          n0
          avatar-id
          (merge-cell-entry avatar (cell/cell-content parent) parent-net)))))
   stable-net
   parent-ids))

(defn- accessor-seed-ids
  [slot-key parent-ids]
  (mapv #(compound-merge/accessor-avatar-id slot-key %) parent-ids))

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
        parent-ids (compound-merge/accessor-parent-ids stable-net slot-key)
        [_ after _updated*] (effect/execute-subnet
                             exec-net
                             (fn [subnet _updated*] subnet)
                             (fn [_subnet]
                               (accessor-seed-ids slot-key seed-parent-ids)))]
    {:executed-net after
     :parent-ids parent-ids}))

(defn- equivalent-to-parent?
  [parent-net parent-id v]
  (and (contains? (net/net-env parent-net) parent-id)
       (sync/strongest-equivalent?
        v
        (net/network-cell-strongest parent-net parent-id)
        parent-net)))

(defn- source-slot-message
  [collection-net slot-key parent-id parent-net]
  (let [v (compound-merge/source-slot-value collection-net slot-key)]
    (if (or (not (compound-merge/source-slot-present? collection-net slot-key))
            (value/unusable? v)
            (not (messageable-parent? parent-net parent-id))
            (equivalent-to-parent? parent-net parent-id v))
      []
      [(message parent-id v)])))

(defn- source-slot-messages
  [collection-net slot-key parent-net]
  (vec (mapcat #(source-slot-message collection-net slot-key % parent-net)
               (compound-merge/accessor-parent-ids collection-net slot-key))))

(defn- projected-accessor-messages
  [executed-net slot-key parent-ids parent-net]
  (->> parent-ids
       (keep (fn [parent-id]
               (let [avatar-id (compound-merge/accessor-avatar-id slot-key parent-id)]
                 (when (and (messageable-parent? parent-net parent-id)
                            (contains? (net/net-env executed-net) avatar-id))
                   (let [v (net/network-cell-strongest executed-net avatar-id)]
                     (when-not (or (value/unusable? v)
                                   (equivalent-to-parent? parent-net parent-id v))
                       (message parent-id v)))))))
       vec))

(defn- accessor-synced?
  [collection-net slot-key parent-net]
  (let [parent-ids* (compound-merge/accessor-parent-ids collection-net slot-key)
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

(defn- network-slot-messages
  [collection-id slot-key parent-id parent-net collection-net]
  (let [known-parent? (contains? (compound-merge/accessor-parent-ids collection-net slot-key)
                                 parent-id)]
    (if-not known-parent?
      (into [(message collection-id
                      (compound-merge/accessor-declaration slot-key parent-id))]
            (source-slot-message collection-net slot-key parent-id parent-net))
      (let [stable-net (compound-merge/refine-accessor-network collection-net)
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
                                               slot-key
                                               parent-ids
                                               parent-net))))))))

(defn network-slot-activation
  [slot-key parent-id collection-id]
  (fn [_inputs _outputs parent-net]
    (let [collection-net (-> parent-net
                             (net/network-cell-strongest collection-id)
                             compound-merge/as-accessor-network)]
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
