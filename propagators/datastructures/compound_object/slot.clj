(ns propagators.datastructures.compound-object.slot
  "Slot synchronization topology for compound objects."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as core]
            [propagators.effectful-execution :as effect]
            [propagators.effectful-sync :as sync]
            [propagators.ids :as ids]
            [propagators.message :refer [message message-id]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn attach-slot-sync
  [collection-net slot-key parent-id _parent-net]
  (let [n (-> collection-net
              (sync/ensure-indexed-cell core/slot-index-key slot-key)
              (sync/ensure-indexed-shell-avatar core/slot-index-key slot-key parent-id))
        dict (net/net-dict-or-empty n)
        avatar-id (get dict parent-id)
        slot-id (get dict slot-key)]
    (sync/attach-indexed-bi-sync n core/slot-sync-key slot-key parent-id avatar-id slot-id)))

(defn- seed-indexed-avatars-for [stable-net index-key slot-key parent-net]
  (reduce
   (fn [n parent-id]
     (sync/ensure-parent-avatar n index-key slot-key parent-id parent-net))
   stable-net
   (filter #(contains? (net/net-env parent-net) %)
           (net/network-indexed-ids stable-net index-key slot-key))))

(defn- seed-indexed-avatars [stable-net slot-key parent-net]
  (seed-indexed-avatars-for stable-net core/slot-index-key slot-key parent-net))

(defn- slot-activation-parent-ids [stable-net slot-key]
  (net/network-indexed-ids stable-net core/slot-index-key slot-key))

(defn- slot-activation-seed-ids [subnet slot-key cell-id]
  (let [dict (net/net-dict-or-empty subnet)
        avatar-ids (keep #(get dict %)
                         (slot-activation-parent-ids subnet slot-key))]
    (if cell-id (conj (vec avatar-ids) cell-id) (vec avatar-ids))))

(defn- execute-slot-subnet [stable-net slot-key parent-net]
  (let [seeded-net (seed-indexed-avatars stable-net slot-key parent-net)
        [_ after updated*] (effect/execute-subnet
                            seeded-net
                            #(effect/hook-output-taps
                              %1
                              (slot-activation-parent-ids %1 slot-key)
                              %2)
                            #(slot-activation-seed-ids
                              %
                              slot-key
                              (net/network-dict-entry % slot-key)))]
    [stable-net after updated*]))

(defn- slot-synced? [collection-net slot-key parent-net]
  (let [slot-value (core/slot-strongest collection-net slot-key)]
    (every?
     (fn [parent-id]
       (sync/strongest-equivalent?
        slot-value
        (net/network-cell-strongest parent-net parent-id)
        parent-net))
     (filter #(contains? (net/net-env parent-net) %)
             (net/network-indexed-ids collection-net core/slot-index-key slot-key)))))

(defn sync-slot-messages
  [collection-id slot-key [exec-net collection-net' updated*]]
  (let [slot-id (net/network-dict-entry exec-net slot-key)
        projected-net (effect/project-stable-cells exec-net collection-net' [slot-id])
        collection-messages (if (sync/strongest-equivalent? exec-net projected-net exec-net)
                              []
                              [(message collection-id projected-net)])
        parent-messages (sync/updated-parent-messages collection-net' updated*)]
    (into collection-messages parent-messages)))

(defn- guarded-slot-messages
  [collection-id slot-key parent-id network collection-net]
  (let [stable-net (attach-slot-sync collection-net slot-key parent-id network)]
    (if (slot-synced? stable-net slot-key network)
      (if (sync/strongest-equivalent? collection-net stable-net network)
        []
        [(message collection-id stable-net)])
      (sync-slot-messages collection-id
                          slot-key
                          (execute-slot-subnet stable-net slot-key network)))))

(defn slot-declarations
  "Declared slot topology indexed by collection id, then slot key, then parent id."
  [n]
  (or (net/network-dict-entry n core/slot-declarations-key) {}))

(defn slot-declarations-for
  "Declared slot topology for one collection id."
  [n collection-id]
  (get (slot-declarations n) collection-id {}))

(defn- record-slot-declaration
  [n collection-id slot-key parent-id prop-id]
  (net/update-net-dict-entry
   n
   core/slot-declarations-key
   #(assoc-in (or % {}) [collection-id slot-key parent-id] {:prop-id prop-id})))

(defn- register-slot-propagator
  [network prop-id activate parent-id collection-id]
  ((prop/construct-propagator
    prop-id
    activate
    [parent-id collection-id]
    [parent-id collection-id])
   network))

(defn- record-registered-slot
  [[installed-id network] collection-id slot-key parent-id]
  [installed-id
   (record-slot-declaration network
                            collection-id
                            slot-key
                            parent-id
                            installed-id)])

(defn slot-activation
  [slot-key parent-id collection-id]
  (fn [_inputs _outputs network]
    (let [collection-net (-> network
                             (net/network-cell-strongest collection-id)
                             core/compound-object)]
      (if (value/contradiction? collection-net)
        [(message collection-id value/contradiction)]
        (filterv #(contains? (net/net-env network) (message-id %))
                 (if (core/read-only-slot? collection-net slot-key)
                   (core/read-only-slot-messages parent-id collection-net slot-key)
                   (guarded-slot-messages collection-id
                                          slot-key
                                          parent-id
                                          network
                                          collection-net)))))))

(defn p:slot
  [slot-key parent-id collection-id]
  (let [prop-id (ids/new-node-id)
        activate (slot-activation slot-key parent-id collection-id)]
    (fn [network]
      (-> network
          (register-slot-propagator prop-id activate parent-id collection-id)
          (record-registered-slot collection-id slot-key parent-id)))))
