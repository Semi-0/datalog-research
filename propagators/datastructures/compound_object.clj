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
(def reduce-sync-key :reduce-sync)
(def slot-declarations-key :slot-declarations)

(def ^:private slot-index-key :slot-index)
(def ^:private reduce-index-key :reduce-index)
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
      (= k reduce-index-key)
      (= k slot-declarations-key)
      (= k read-only-slots-key)
      (ids/node-id? k)
      (and (vector? k)
           (contains? #{slot-sync-key reduce-sync-key} (first k)))))

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

(defn- seed-indexed-avatars-for [stable-net index-key slot-key parent-net]
  (reduce
   (fn [n parent-id]
     (sync/ensure-parent-avatar n index-key slot-key parent-id parent-net))
   stable-net
   (filter #(contains? (net/net-env parent-net) %)
           (net/network-indexed-ids stable-net index-key slot-key))))

(defn- seed-indexed-avatars [stable-net slot-key parent-net]
  (seed-indexed-avatars-for stable-net slot-index-key slot-key parent-net))

(defn- slot-activation-parent-ids [stable-net slot-key]
  (net/network-indexed-ids stable-net slot-index-key slot-key))

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

(defn slot-declarations
  "Declared slot topology indexed by collection id, then slot key, then parent id."
  [n]
  (or (net/network-dict-entry n slot-declarations-key) {}))

(defn slot-declarations-for
  "Declared slot topology for one collection id."
  [n collection-id]
  (get (slot-declarations n) collection-id {}))

(defn- record-slot-declaration
  [n collection-id slot-key parent-id prop-id]
  (net/update-net-dict-entry
   n
   slot-declarations-key
   #(assoc-in (or % {}) [collection-id slot-key parent-id] {:prop-id prop-id})))

(defn p:slot
  [slot-key parent-id collection-id]
  (let [prop-id (ids/new-node-id)
        activate (fn [_inputs _outputs network]
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
                                                         collection-net))))))]
    (fn [network]
      (let [[installed-id n] ((prop/construct-propagator
                               prop-id
                               activate
                               [parent-id collection-id]
                               [parent-id collection-id])
                              network)]
        [installed-id
         (record-slot-declaration n
                                  collection-id
                                  slot-key
                                  parent-id
                                  installed-id)]))))

(defn- cell-id?
  [n id]
  (cell/cell? (get (net/net-env n) id)))

(defn- ensure-dict-cell
  [n dict-key]
  (if (net/network-dict-entry n dict-key)
    n
    (let [cell-id (ids/new-node-id)]
      (-> n
          (nb/install-cell cell-id)
          (net/assoc-net-dict-entry dict-key cell-id)))))

(defn- reducible-slot-keys
  [source-net]
  (->> (public-slot-keys source-net)
       (filter (fn [slot-key]
                 (cell-id? source-net
                           (net/network-dict-entry source-net slot-key))))
       (sort-by pr-str)
       vec))

(defn- reducer-public-source
  [source-net]
  (let [slot-keys (reducible-slot-keys source-net)
        dict (net/net-dict-or-empty source-net)
        slot-ids (keep #(get dict %) slot-keys)
        public-dict (into (select-keys dict slot-keys)
                          [[slot-index-key (zipmap slot-keys (repeat #{}))]])]
    (net/net {}
             (select-keys (net/net-env source-net) slot-ids)
             public-dict)))

(defn- reducer-boundary-ids
  [merge-net-id init-id out-id]
  [merge-net-id init-id out-id])

(defn- reducer-slot-avatar-key
  [reducer-key slot-key]
  [reduce-sync-key reducer-key :slot-avatar slot-key])

(defn- reducer-slot->avatar-key
  [reducer-key slot-key]
  [reduce-sync-key reducer-key :slot->avatar slot-key])

(defn- reducer-avatar->slot-key
  [reducer-key slot-key]
  [reduce-sync-key reducer-key :avatar->slot slot-key])

(defn- attach-reducer-boundaries
  [source-net reducer-key merge-net-id init-id out-id]
  (reduce
   (fn [n parent-id]
     (sync/ensure-indexed-shell-avatar n reduce-index-key reducer-key parent-id))
   source-net
   (reducer-boundary-ids merge-net-id init-id out-id)))

(defn- attach-reducer-slot-accessor
  [source-net reducer-key slot-key]
  (let [slot-id (net/network-dict-entry source-net slot-key)
        n (ensure-dict-cell source-net
                            (reducer-slot-avatar-key reducer-key slot-key))
        dict (net/net-dict-or-empty n)
        avatar-id (get dict (reducer-slot-avatar-key reducer-key slot-key))]
    (-> n
        (sync/attach-fast-bi-sync slot-id
                                  avatar-id
                                  (reducer-slot->avatar-key reducer-key slot-key)
                                  (reducer-avatar->slot-key reducer-key slot-key))
        (net/update-net-dict-entry reduce-index-key
                                   #(update (or % {})
                                            slot-key
                                            (fnil conj #{})
                                            reducer-key)))))

(defn- attach-reducer-accessors
  [source-net reducer-key merge-net-id init-id out-id]
  (let [with-boundaries (attach-reducer-boundaries source-net
                                                   reducer-key
                                                   merge-net-id
                                                   init-id
                                                   out-id)]
    (reduce
     (fn [n slot-key]
       (attach-reducer-slot-accessor n
                                     reducer-key
                                     slot-key))
     with-boundaries
     (reducible-slot-keys with-boundaries))))

(defn- reducer-output-content
  [source-net merge-net init]
  (when-not (or (value/unusable? merge-net)
                (value/contradiction? init))
    (reducer/reducer-subnet (reducer-public-source source-net) merge-net init)))

(defn p:reduce
  "Install reducer accessors in source, then emit reduced source content.

  Each activation first makes the source compound object's internal reducer
  topology complete for the current public slots. It then emits reducer-subnet
  content whose strongest value folds those slots through `merge-net`, using
  fixed merge-net dict keys `:acc`, `:update`, and `:out`."
  [source-id merge-net-id init-id out-id]
  (let [reducer-key (ids/new-node-id)]
    (prop/construct-propagator
     (fn [_inputs _outputs network]
       (let [source (compound-object (net/network-cell-strongest network source-id))]
         (if (value/contradiction? source)
           [(message source-id value/contradiction)]
           (let [merge-net (net/network-cell-strongest network merge-net-id)
                 init (net/network-cell-strongest network init-id)
                 stable-net (attach-reducer-accessors source
                                                      reducer-key
                                                      merge-net-id
                                                      init-id
                                                      out-id)
                 source-messages (if (sync/strongest-equivalent?
                                      source
                                      stable-net
                                      network)
                                   []
                                   [(message source-id stable-net)])
                 out-content (reducer-output-content stable-net merge-net init)
                 out-messages (if out-content
                                [(message out-id out-content)]
                                [])]
             (into source-messages out-messages)))))
     [source-id merge-net-id init-id]
     [source-id out-id])))

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
