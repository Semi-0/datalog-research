(ns propagators.datastructures.compound-object.reduce
  "Reducer topology over compound-object public slots."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as core]
            [propagators.datastructures.compound-object.network-slot :as network-slot]
            [propagators.datastructures.reducer-subnet :as reducer]
            [propagators.effectful-sync :as sync]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

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
  (->> (core/public-slot-keys source-net)
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
                          [[core/slot-index-key (zipmap slot-keys (repeat #{}))]])]
    (net/net {}
             (select-keys (net/net-env source-net) slot-ids)
             public-dict)))

(defn- declared-accessor-input-ids
  [n source-id]
  (->> (network-slot/accessor-declarations-for n source-id)
       vals
       (mapcat keys)
       (filter #(and (contains? (net/net-env n) %)
                     (contains? (net/net-graph n) %)))
       distinct
       (sort-by pr-str)
       vec))

(defn- accessor-slot-value
  [network source-net slot-key]
  (let [parent-values (->> (network-slot/accessor-parent-ids source-net slot-key)
                           (filter #(contains? (net/net-env network) %))
                           (map #(net/network-cell-strongest network %))
                           (remove value/unusable?)
                           vec)]
    (cond
      (seq parent-values)
      (reduce (fn [acc v]
                (if (= acc v)
                  acc
                  value/contradiction))
              (first parent-values)
              (rest parent-values))

      (network-slot/source-slot-present? source-net slot-key)
      (network-slot/source-slot-value source-net slot-key)

      :else value/nothing)))

(defn- add-reducer-source-slot
  [n slot-key slot-value]
  (if (value/unusable? slot-value)
    n
    (let [slot-id (ids/new-node-id)]
      (-> n
          (nb/install-cell slot-id slot-value slot-value)
          (net/assoc-net-dict-entry slot-key slot-id)
          (net/update-net-dict-entry core/slot-index-key
                                     #(assoc (or % {}) slot-key #{}))))))

(defn- reducer-accessor-source
  [network source-net]
  (reduce
   (fn [n slot-key]
     (add-reducer-source-slot n
                              slot-key
                              (accessor-slot-value network source-net slot-key)))
   (net/net-with-dict net/empty-net {core/slot-index-key {}})
   (sort-by pr-str (network-slot/accessor-slot-keys source-net))))

(defn- reducer-boundary-ids
  [merge-net-id init-id out-id]
  [merge-net-id init-id out-id])

(defn- reducer-slot-avatar-key
  [reducer-key slot-key]
  [core/reduce-sync-key reducer-key :slot-avatar slot-key])

(defn- reducer-slot->avatar-key
  [reducer-key slot-key]
  [core/reduce-sync-key reducer-key :slot->avatar slot-key])

(defn- reducer-avatar->slot-key
  [reducer-key slot-key]
  [core/reduce-sync-key reducer-key :avatar->slot slot-key])

(defn- attach-reducer-boundaries
  [source-net reducer-key merge-net-id init-id out-id]
  (reduce
   (fn [n parent-id]
     (sync/ensure-indexed-shell-avatar n core/reduce-index-key reducer-key parent-id))
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
        (net/update-net-dict-entry core/reduce-index-key
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

(defn- reducer-accessor-output-content
  [network source-net merge-net init]
  (when-not (or (value/unusable? merge-net)
                (value/contradiction? init))
    (reducer/reducer-subnet (reducer-accessor-source network source-net)
                            merge-net
                            init)))

(defn- reduce-activation
  [reducer-key source-id merge-net-id init-id out-id]
  (fn [_inputs _outputs network]
    (let [raw-source (net/network-cell-strongest network source-id)
          merge-net (net/network-cell-strongest network merge-net-id)
          init (net/network-cell-strongest network init-id)]
      (cond
        (value/contradiction? raw-source)
        [(message source-id value/contradiction)]

        (network-slot/accessor-network? raw-source)
        (let [out-content (reducer-accessor-output-content network
                                                           raw-source
                                                           merge-net
                                                           init)]
          (if out-content [(message out-id out-content)] []))

        :else
        (let [source (core/compound-object raw-source)]
          (if (value/contradiction? source)
            [(message source-id value/contradiction)]
            (let [stable-net (attach-reducer-accessors source
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
              (into source-messages out-messages))))))))

(defn p:reduce
  "Install reducer accessors in source, then emit reduced source content.

  Each activation first makes the source compound object's internal reducer
  topology complete for the current public slots. It then emits reducer-subnet
  content whose strongest value folds those slots through `merge-net`, using
  fixed merge-net dict keys `:acc`, `:update`, and `:out`."
  [source-id merge-net-id init-id out-id]
  (let [reducer-key (ids/new-node-id)]
    (fn [n]
      (let [accessor-inputs (declared-accessor-input-ids n source-id)
            inputs (vec (distinct (concat [source-id merge-net-id init-id]
                                          accessor-inputs)))]
        ((prop/construct-propagator
          (reduce-activation reducer-key source-id merge-net-id init-id out-id)
          inputs
          [source-id out-id])
         n)))))
