(ns propagators.datastructures.compound-object
  "Experimental object slots over named-network cell values."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.named-network :as named]
            [propagators.datastructures.reducer-subnet :as reducer]
            [propagators.effectful-execution :as effect]
            [propagators.effectful-sync :as sync]
            [propagators.ids :as ids]
            [propagators.message :refer [message message-id]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def slot-sync-key :slot-sync)

(def ^:private slot-index-key :slot-index)

(defn empty-cons-net
  "A named network with stable `:car` and `:cdr` slot cells."
  []
  (let [car-id (ids/new-node-id)
        cdr-id (ids/new-node-id)]
    (-> net/empty-net
        (nb/install-cell car-id)
        (nb/install-cell cdr-id)
        (net/net-with-dict {:car car-id
                            :cdr cdr-id
                            :slot-index {:car #{}
                                         :cdr #{}}}))))

(defn ensure-cons-net
  [x]
  (cond
    (value/nothing? x) (empty-cons-net)
    (named/named-network? x) x
    (value/contradiction? x) value/contradiction
    :else value/contradiction))

(defn slot-cell-id [collection-net slot-key]
  (net/network-dict-entry collection-net slot-key))

(defn slot-strongest [collection-net slot-key]
  (net/network-cell-strongest collection-net (slot-cell-id collection-net slot-key)))

(defn slot-content [collection-net slot-key]
  (net/network-cell-content collection-net (slot-cell-id collection-net slot-key)))

(defn attach-slot-sync
  [collection-net slot-key parent-id _parent-net]
  (let [n (-> collection-net
              (sync/ensure-indexed-cell slot-index-key slot-key)
              (sync/ensure-indexed-shell-avatar slot-index-key slot-key parent-id))
        dict (net/net-dict-or-empty n)
        avatar-id (get dict parent-id)
        slot-id (get dict slot-key)]
    (sync/attach-indexed-bi-sync n slot-sync-key slot-key parent-id avatar-id slot-id)))

(defn- seed-indexed-avatars [stable-net slot-key parent-net]
  (reduce
   (fn [n parent-id]
     (sync/ensure-parent-avatar n slot-index-key slot-key parent-id parent-net))
   stable-net
   (filter #(contains? (net/net-env parent-net) %)
           (net/network-indexed-ids stable-net slot-index-key slot-key))))

(defn- execute-slot-subnet [stable-net slot-key parent-net]
  (let [seeded-net (seed-indexed-avatars stable-net slot-key parent-net)
        [_ after updated*] (effect/execute-subnet
                            seeded-net
                            #(effect/hook-output-taps
                              %1
                              (net/network-indexed-ids %1 slot-index-key slot-key)
                              %2)
                            #(sync/indexed-seed-ids
                              %
                              slot-index-key
                              slot-key
                              (net/network-dict-entry % slot-key)))]
    [stable-net after updated*]))

(defn- slot-synced? [collection-net slot-key parent-net]
  (let [slot-value (slot-strongest collection-net slot-key)]
    (every?
     (fn [parent-id]
       (sync/strongest-equivalent?
        slot-value
        (net/network-cell-strongest parent-net parent-id)
        parent-net))
     (filter #(contains? (net/net-env parent-net) %)
             (net/network-indexed-ids collection-net slot-index-key slot-key)))))

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

(defn p:slot
  [slot-key parent-id collection-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [collection-net (-> network
                              (net/network-cell-strongest collection-id)
                              ensure-cons-net)]
       (if (value/contradiction? collection-net)
         [(message collection-id value/contradiction)]
         (filterv #(contains? (net/net-env network) (message-id %))
                  (guarded-slot-messages collection-id slot-key parent-id network collection-net)))))
   [parent-id collection-id]
   [parent-id collection-id]))

(defn p:reduce
  "Construct reducer-subnet content from source, merge-net, and init cells.

  The emitted reducer-subnet has exactly `{:source source :merge-net merge-net
  :init init}`. Its strongest value folds all usable public slots in `source`
  through `merge-net`, using fixed merge-net dict keys `:acc`, `:update`, and
  `:out`."
  [source-id merge-net-id init-id out-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [source (net/network-cell-strongest network source-id)
           merge-net (net/network-cell-strongest network merge-net-id)
           init (net/network-cell-strongest network init-id)]
       (if (or (value/unusable? source)
               (value/unusable? merge-net)
               (value/unusable? init))
         []
         [(message out-id (reducer/reducer-subnet source merge-net init))])))
   [source-id merge-net-id init-id]
   [out-id]))

(defn p:car [elem-id collection-id]
  (p:slot :car elem-id collection-id))

(defn p:cdr [elem-id collection-id]
  (p:slot :cdr elem-id collection-id))

(defn p:cons
  [head-id tail-id collection-id]
  (fn [network]
    (let [[car-prop n] (nb/install-propagator network (p:car head-id collection-id))
          [cdr-prop n] (nb/install-propagator n (p:cdr tail-id collection-id))]
      [[car-prop cdr-prop] n])))
