(ns propagators.datastructures.compound-object
  "Experimental object slots over named-network cell values."
  (:require [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.datastructures.named-network :as named]
            [propagators.effectful-execution :as effect]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib :as stdlib]))

(def slot-keys #{:car :cdr})

(def slot-sync-key :slot-sync)

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

(defn- ensure-slot-cell [collection-net slot-key]
  (if (slot-cell-id collection-net slot-key)
    collection-net
    (let [id (ids/new-node-id)]
      (-> collection-net
          (nb/install-cell id)
          (net/assoc-net-dict-entry slot-key id)
          (net/update-net-dict-entry :slot-index #(assoc (or % {}) slot-key #{}))))))

(defn- ensure-stable-parent-avatar
  "Ensure `parent-id` has a durable avatar cell without storing parent evidence."
  [subnet slot-key parent-id]
  (let [dict (net/net-dict-or-empty subnet)]
    (if (get dict parent-id)
      (net/update-net-dict-entry subnet :slot-index
                                 #(update (or % {}) slot-key (fnil conj #{}) parent-id))
      (let [avatar-id (ids/new-node-id)]
        (-> subnet
            (nb/install-cell avatar-id)
            (net/assoc-net-dict-entry parent-id avatar-id)
            (net/update-net-dict-entry :slot-index
                                       #(update (or % {}) slot-key (fnil conj #{}) parent-id)))))))

(defn- attach-fast-bi-sync
  [subnet from-id to-id from->to-key to->from-key]
  (let [dict (net/net-dict-or-empty subnet)]
    (if (and (contains? dict from->to-key)
             (contains? dict to->from-key))
      subnet
      (let [[[from->to to->from] subnet'] (stdlib/fast-bi-sync subnet from-id to-id)]
        (-> subnet'
            (net/assoc-net-dict-entry from->to-key from->to)
            (net/assoc-net-dict-entry to->from-key to->from))))))

(defn attach-slot-sync
  [collection-net slot-key parent-id _parent-net]
  (let [n (-> collection-net
              (ensure-slot-cell slot-key)
              (ensure-stable-parent-avatar slot-key parent-id))
        dict (net/net-dict-or-empty n)
        avatar-id (get dict parent-id)
        slot-id (get dict slot-key)]
    (attach-fast-bi-sync n
                         avatar-id
                         slot-id
                         [slot-sync-key slot-key parent-id :from->to]
                         [slot-sync-key slot-key parent-id :to->from])))

(defn- slot-seed-ids [subnet slot-key]
  (let [dict (net/net-dict-or-empty subnet)
        parent-ids (vec (net/network-indexed-ids subnet :slot-index slot-key))
        avatar-ids (keep #(get dict %) parent-ids)]
    (if-let [slot-id (net/network-dict-entry subnet slot-key)]
      (conj (vec avatar-ids) slot-id)
      (vec avatar-ids))))

(defn- stable-slot-cell-ids [exec-net slot-key]
  [(net/network-dict-entry exec-net slot-key)])

(defn- slot-output-taps [subnet slot-key updated*]
  (effect/hook-output-taps subnet
                           (net/network-indexed-ids subnet :slot-index slot-key)
                           updated*))

(defn- parent-entry [parent-net parent-id]
  (net/network-env-lookup parent-net parent-id))

(defn- seed-indexed-avatars
  [stable-net slot-key parent-net]
  (reduce
   (fn [n parent-id]
     (if-let [avatar-id (net/network-dict-entry n parent-id)]
       (net/assoc-net-cell n avatar-id (parent-entry parent-net parent-id))
       n))
   stable-net
   (net/network-indexed-ids stable-net :slot-index slot-key)))

(defn- execute-slot-subnet
  [stable-net slot-key parent-net]
  (let [seeded-net (seed-indexed-avatars stable-net slot-key parent-net)
        [_ after updated*] (effect/execute-subnet
                            seeded-net
                            #(slot-output-taps %1 slot-key %2)
                            #(slot-seed-ids % slot-key))]
    [stable-net after updated*]))

(defn- updated-parent-messages
  "Messages from updated avatar cells back to their outer parent cells."
  [after updated*]
  (let [dict (net/net-dict-or-empty after)]
    (mapv (fn [parent-id]
            (let [avatar-id (get dict parent-id)]
              (message parent-id (net/network-cell-strongest after avatar-id))))
          (sort-by pr-str @updated*))))

(defn- strongest-equivalent?
  [a b network]
  (false? (merge/cell-updated? a b network)))

(defn- slot-synced?
  [collection-net slot-key parent-net]
  (let [slot-value (slot-strongest collection-net slot-key)]
    (every?
     (fn [parent-id]
       (strongest-equivalent? slot-value
                              (net/network-cell-strongest parent-net parent-id)
                              parent-net))
     (net/network-indexed-ids collection-net :slot-index slot-key))))

(defn- collection-message-needed?
  [collection-net stable-net network]
  (not (strongest-equivalent? stable-net collection-net network)))

(defn sync-slot-messages
  [collection-id slot-key [exec-net collection-net' updated*]]
  (let [projected-net (effect/project-stable-cells
                       exec-net
                       collection-net'
                       (stable-slot-cell-ids exec-net slot-key))
        collection-messages (if (collection-message-needed? exec-net projected-net exec-net)
                              [(message collection-id projected-net)]
                              [])
        parent-messages (updated-parent-messages collection-net' updated*)]
    (into collection-messages parent-messages)))

(defn- guarded-slot-messages
  [collection-id slot-key parent-id network collection-net]
  (let [stable-net (attach-slot-sync collection-net slot-key parent-id network)
        topology-changed? (collection-message-needed? collection-net stable-net network)]
    (if (slot-synced? stable-net slot-key network)
      (if topology-changed?
        [(message collection-id stable-net)]
        [])
      (let [messages (sync-slot-messages collection-id
                                         slot-key
                                         (execute-slot-subnet stable-net
                                                              slot-key
                                                              network))]
        messages))))

(defn p:slot
  [slot-key parent-id collection-id]
  (when-not (contains? slot-keys slot-key)
    (throw (ex-info "unknown compound object slot" {:slot-key slot-key})))
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [collection-net (-> network
                              (net/network-cell-strongest collection-id)
                              ensure-cons-net)]
       (if (value/contradiction? collection-net)
         [(message collection-id value/contradiction)]
         (guarded-slot-messages collection-id slot-key parent-id network collection-net))))
   [parent-id collection-id]
   [parent-id collection-id]))

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
