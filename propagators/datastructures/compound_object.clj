(ns propagators.datastructures.compound-object
  "Experimental object slots over named-network cell values."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
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
(def ^:private read-only-slots-key :read-only-slots)

(defn empty-compound-object
  "A named network with no public slots."
  []
  (net/net-with-dict net/empty-net {slot-index-key {}}))

(defn- add-slot-cell
  [n slot-key slot-value]
  (let [slot-id (or (net/network-dict-entry n slot-key)
                    (ids/new-node-id))
        n' (if (contains? (net/net-env n) slot-id)
             n
             (nb/install-cell n slot-id slot-value slot-value))]
    (-> n'
        (net/assoc-net-cell slot-id (cell/cell slot-value slot-value))
        (net/assoc-net-dict-entry slot-key slot-id)
        (net/update-net-dict-entry slot-index-key
                                   #(assoc (or % {}) slot-key #{})))))

(defn- map-compound-object
  [m]
  (reduce-kv add-slot-cell (empty-compound-object) m))

(defn- vector-compound-object
  [v]
  (-> (reduce-kv add-slot-cell (empty-compound-object) v)
      (add-slot-cell :count (count v))
      (net/assoc-net-dict-entry read-only-slots-key #{:count})))

(defn compound-object
  "Normalize ordinary compound values into the named-network slot representation."
  [x]
  (cond
    (value/nothing? x) (empty-compound-object)
    (named/named-network? x) x
    (or (net/net? x)
        (ids/node-id? x)
        (cell/cell? x)
        (prop/prop? x)) value/contradiction
    (vector? x) (vector-compound-object x)
    (map? x) (map-compound-object x)
    (value/contradiction? x) value/contradiction
    :else value/contradiction))

(defn empty-cons-net
  "A named network with stable `:car` and `:cdr` slot cells."
  []
  (-> (empty-compound-object)
      (add-slot-cell :car value/nothing)
      (add-slot-cell :cdr value/nothing)))

(defn ensure-cons-net
  [x]
  (compound-object x))

(defn slot-cell-id [collection slot-key]
  (let [collection-net (compound-object collection)]
    (when-not (value/contradiction? collection-net)
      (net/network-dict-entry collection-net slot-key))))

(defn slot-strongest [collection slot-key]
  (let [collection-net (compound-object collection)]
    (when-not (value/contradiction? collection-net)
      (when-let [id (slot-cell-id collection-net slot-key)]
        (net/network-cell-strongest collection-net id)))))

(defn slot-content [collection slot-key]
  (let [collection-net (compound-object collection)]
    (when-not (value/contradiction? collection-net)
      (when-let [id (slot-cell-id collection-net slot-key)]
        (net/network-cell-content collection-net id)))))

(def slot-value slot-strongest)

(defn- internal-slot-key?
  [k]
  (or (= k slot-index-key)
      (= k read-only-slots-key)
      (ids/node-id? k)
      (and (vector? k)
           (= slot-sync-key (first k)))))

(defn public-slot-keys
  [collection]
  (let [collection-net (compound-object collection)]
    (if (value/contradiction? collection-net)
      #{}
      (->> (keys (net/net-dict-or-empty collection-net))
           (remove internal-slot-key?)
           set))))

(defn- read-only-slot?
  [collection-net slot-key]
  (contains? (get (net/net-dict-or-empty collection-net) read-only-slots-key #{})
             slot-key))

(defn- read-only-slot-messages
  [parent-id collection-net slot-key]
  (let [slot-value (slot-strongest collection-net slot-key)]
    (if (value/unusable? slot-value)
      []
      [(message parent-id slot-value)])))

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
                              compound-object)]
       (if (value/contradiction? collection-net)
         [(message collection-id value/contradiction)]
         (filterv #(contains? (net/net-env network) (message-id %))
                  (if (read-only-slot? collection-net slot-key)
                    (read-only-slot-messages parent-id collection-net slot-key)
                    (guarded-slot-messages collection-id
                                           slot-key
                                           parent-id
                                           network
                                           collection-net))))))
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
     (let [source (compound-object (net/network-cell-strongest network source-id))
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
