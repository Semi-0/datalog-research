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
            [propagators.propagator :as prop]))

(def accessor-network-key
  (core/internal-metadata-key :accessor-network))

(def source-slots-key
  (core/internal-metadata-key :source-slots))

(def accessor-sync-key
  (core/internal-metadata-key :accessor-sync))

(def accessor-subscribers-key
  (core/internal-metadata-key :accessor-subscribers))

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
    source-slots-key {}
    accessor-subscribers-key {}}))

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
      (net/update-net-dict-entry source-slots-key #(or % {}))
      (net/update-net-dict-entry accessor-subscribers-key #(or % {}))))

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
                 (keys (or (net/network-dict-entry n accessor-subscribers-key)
                           {}))
                 (filter #(public-slot-present? n %)
                         (core/public-slot-keys n))))))

(declare externalize-accessor-value*)

(def ^:private externalize-missing ::externalize-missing)

(defn- usable-or-missing
  [v]
  (if (value/unusable? v)
    externalize-missing
    v))

(defn- cell-value-or-missing
  [n id]
  (if (and (net/net? n)
           (contains? (net/net-env n) id))
    (usable-or-missing (net/network-cell-strongest n id))
    externalize-missing))

(defn- public-slot-value-or-missing
  [n slot-key]
  (if-let [id (slot-cell-id n slot-key)]
    (cell-value-or-missing n id)
    externalize-missing))

(defn- source-slot-value-or-missing
  [n slot-key]
  (if (contains? (source-slots n) slot-key)
    (usable-or-missing (get (source-slots n) slot-key))
    externalize-missing))

(defn- parent-slot-value-or-missing
  [parent-net accessor-net slot-key]
  (loop [parent-ids (seq (sort-by pr-str
                                   (accessor-parent-ids accessor-net
                                                        slot-key)))]
    (if-not parent-ids
      externalize-missing
      (let [v (cell-value-or-missing parent-net (first parent-ids))]
        (if (= externalize-missing v)
          (recur (next parent-ids))
          v)))))

(defn- externalized-slot-value
  [parent-net seen accessor-net slot-key]
  (let [parent-v (parent-slot-value-or-missing parent-net accessor-net slot-key)]
    (if-not (= externalize-missing parent-v)
      (externalize-accessor-value* parent-net seen parent-v)
      (let [public-v (public-slot-value-or-missing accessor-net slot-key)]
        (if-not (= externalize-missing public-v)
          (externalize-accessor-value* parent-net seen public-v)
          (let [source-v (source-slot-value-or-missing accessor-net slot-key)]
            (if (= externalize-missing source-v)
              externalize-missing
              (externalize-accessor-value* parent-net seen source-v))))))))

(defn- externalized-source-slots
  [parent-net seen accessor-net]
  (into {}
        (keep (fn [slot-key]
                (let [v (externalized-slot-value parent-net
                                                 seen
                                                 accessor-net
                                                 slot-key)]
                  (when-not (= externalize-missing v)
                    [slot-key v]))))
        (accessor-slot-keys accessor-net)))

(defn externalize-accessor-value*
  [parent-net seen v]
  (if-not (accessor-network? v)
    v
    (let [token (System/identityHashCode v)]
      (if (contains? seen token)
        v
        (let [accessor-net (ensure-accessor-metadata v)
              source-values (externalized-source-slots parent-net
                                                       (conj seen token)
                                                       accessor-net)]
          (if (empty? source-values)
            accessor-net
            (net/update-net-dict-entry accessor-net
                                       source-slots-key
                                       #(merge (or % {}) source-values))))))))

(defn externalize-accessor-value
  "Return `v` with accessor-network slot values snapshotted into source slots.

  This is an explicit boundary operation for moving an accessor shell as a
  detached value. Live accessor propagation remains cell-based; this only makes
  a copied accessor network self-contained enough for later public slot reads.
  "
  [parent-net v]
  (externalize-accessor-value* parent-net #{} v))

(defn externalize-accessor-network
  [parent-net accessor-net]
  (externalize-accessor-value parent-net accessor-net))

(defn externalize-accessor-cell
  [parent-net collection-id]
  (externalize-accessor-value parent-net
                              (net/network-cell-strongest parent-net
                                                           collection-id)))

(defn accessor-subscriber-refs
  [n slot-key]
  (get-in (net/net-dict-or-empty n)
          [accessor-subscribers-key slot-key]
          #{}))

(defn attach-accessor-subscriber
  [collection-net slot-key subscriber-ref]
  (-> collection-net
      ensure-accessor-metadata
      (net/update-net-dict-entry
       accessor-subscribers-key
       #(update (or % {}) slot-key (fnil conj #{}) subscriber-ref))))

(defn- attach-accessor-subscribers
  [collection-net slot-key subscriber-refs]
  (reduce #(attach-accessor-subscriber %1 slot-key %2)
          collection-net
          subscriber-refs))

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
    {:stable-net stable-net
     :executed-net after
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
  (if-not (source-slot-present? collection-net slot-key)
    []
    (let [v (source-slot-value collection-net slot-key)]
      (if (value/unusable? v)
        []
        (->> (accessor-parent-ids collection-net slot-key)
             (filter #(network-cell-present? parent-net %))
             (remove #(equivalent-to-parent? parent-net % v))
             (mapv #(message % (externalize-accessor-value parent-net
                                                            v))))))))

(defn- projected-accessor-messages
  [executed-net parent-ids parent-net]
  (let [dict (net/net-dict-or-empty executed-net)]
    (->> parent-ids
         (keep (fn [parent-id]
                 (when-let [avatar-id (and (network-cell-present? parent-net parent-id)
                                           (get dict parent-id))]
                   (let [v (net/network-cell-strongest executed-net avatar-id)]
                     (when-not (equivalent-to-parent? parent-net parent-id v)
                       (message parent-id
                                (externalize-accessor-value parent-net
                                                            v)))))))
         vec)))

(def ^:private missing-slot-value ::missing-slot-value)

(defn- first-usable-value
  [values]
  (loop [xs (seq values)]
    (cond
      (nil? xs)
      missing-slot-value

      (value/unusable? (first xs))
      (recur (next xs))

      :else
      (first xs))))

(defn- executed-slot-value
  [executed-net parent-ids]
  (let [dict (net/net-dict-or-empty executed-net)]
    (first-usable-value
     (keep (fn [parent-id]
             (when-let [avatar-id (get dict parent-id)]
               (net/network-cell-strongest executed-net avatar-id)))
           (sort-by pr-str parent-ids)))))

(defn- source-slot-value*
  [collection-net slot-key]
  (if-not (source-slot-present? collection-net slot-key)
    missing-slot-value
    (let [v (source-slot-value collection-net slot-key)]
      (if (value/unusable? v)
        missing-slot-value
        v))))

(defn- subscriber-slot-value
  [stable-net executed-net slot-key parent-ids]
  (let [v (executed-slot-value executed-net parent-ids)]
    (if (= missing-slot-value v)
      (source-slot-value* stable-net slot-key)
      v)))

(defn- subscriber-messages
  [stable-net executed-net slot-key parent-ids parent-net]
  (let [v (subscriber-slot-value stable-net executed-net slot-key parent-ids)]
    (if (= missing-slot-value v)
      []
      (mapv #(message % (externalize-accessor-value parent-net v))
            (sort-by pr-str
                     (accessor-subscriber-refs stable-net slot-key))))))

(defn- topology-message
  [collection-id before after parent-net]
  (when-not (sync/strongest-equivalent? before after parent-net)
    (message collection-id after)))

(defn- accessor-synced?
  [collection-net slot-key parent-net]
  (let [parent-ids (filter #(network-cell-present? parent-net %)
                           (accessor-parent-ids collection-net slot-key))]
    (or (empty? parent-ids)
        (let [baseline (net/network-cell-strongest parent-net (first parent-ids))]
          (every? #(sync/strongest-equivalent?
                    baseline
                    (net/network-cell-strongest parent-net %)
                    parent-net)
                  (rest parent-ids))))))

(defn attach-network-slot-sync
  [collection-net slot-key parent-id _parent-net]
  (ensure-accessor-route (as-accessor-network collection-net) slot-key parent-id))

(defn network-slot-activation
  [slot-key parent-id collection-id subscriber-refs notify-subscribers?]
  (fn [_inputs _outputs parent-net]
    (let [collection-net (-> parent-net
                             (net/network-cell-strongest collection-id)
                             as-accessor-network)]
      (if (value/contradiction? collection-net)
        [(message collection-id value/contradiction)]
        (let [known-parent? (contains? (accessor-parent-ids collection-net slot-key)
                                       parent-id)
              stable-net (-> collection-net
                             (ensure-accessor-route slot-key parent-id)
                             (attach-accessor-subscribers slot-key subscriber-refs))
              canonical-id (canonical-parent-id stable-net slot-key)
              seed-parent-ids (if known-parent?
                                [parent-id]
                                (vec (distinct [parent-id canonical-id])))
              source-messages (source-slot-messages stable-net slot-key parent-net)
              collection-message (topology-message collection-id
                                                   collection-net
                                                   stable-net
                                                   parent-net)
              subscribers? (and notify-subscribers?
                                (seq (accessor-subscriber-refs stable-net
                                                               slot-key)))]
          (if (and known-parent?
                   (empty? source-messages)
                   (nil? collection-message)
                   (not subscribers?)
                   (accessor-synced? stable-net slot-key parent-net))
            []
            (let [{:keys [executed-net parent-ids]}
                  (run-accessor-inner-net stable-net
                                          slot-key
                                          parent-net
                                          seed-parent-ids)
                  accessor-messages (into source-messages
                                          (projected-accessor-messages executed-net
                                                                       parent-ids
                                                                       parent-net))
                  subscriber-messages (if notify-subscribers?
                                        (subscriber-messages stable-net
                                                             executed-net
                                                             slot-key
                                                             parent-ids
                                                             parent-net)
                                        [])]
              (cond-> accessor-messages
                (seq subscriber-messages) (into subscriber-messages)
                collection-message (conj collection-message)))))))))

(defn- record-network-slot-declaration
  [n collection-id slot-key parent-id prop-id]
  (net/update-net-dict-entry
   n
   core/slot-declarations-key
   #(assoc-in (or % {}) [collection-id slot-key parent-id]
              {:prop-id prop-id :strategy :network-slot})))

(defn- register-network-slot-propagator
  [network prop-id activate parent-id collection-id]
  ((prop/construct-propagator
    prop-id
    activate
    [parent-id collection-id]
    [parent-id collection-id])
   network))

(defn p:network-slot
  ([slot-key parent-id collection-id]
   (p:network-slot slot-key parent-id collection-id {}))
  ([slot-key parent-id collection-id {:keys [subscriber-refs]
                                      :or {subscriber-refs []}
                                      :as opts}]
   (let [prop-id (ids/new-node-id)
         activate (network-slot-activation slot-key
                                           parent-id
                                           collection-id
                                           subscriber-refs
                                           (get opts :notify-subscribers? true))]
     (fn [network]
       (let [[installed-id network']
             (register-network-slot-propagator network prop-id activate parent-id collection-id)]
         [installed-id
          (record-network-slot-declaration network'
                                           collection-id
                                           slot-key
                                           parent-id
                                           installed-id)])))))

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
