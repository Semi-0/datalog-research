(ns propagators.datastructures.compound-object.core
  "Core compound-object representation and accessors."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.named-network :as named]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def slot-sync-key :slot-sync)
(def reduce-sync-key :reduce-sync)
(def slot-declarations-key :slot-declarations)
(def internal-metadata-prefix :compound/internal)

(defn internal-metadata-key
  [& path]
  (into [internal-metadata-prefix] path))

(def slot-index-key :slot-index)
(def reduce-index-key :reduce-index)
(def read-only-slots-key :read-only-slots)

(defn empty-compound-object
  "A named network with no public slots."
  []
  (net/net-with-dict net/empty-net {slot-index-key {}}))

(defn add-slot-cell
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

(defn map-compound-object
  [m]
  (reduce-kv add-slot-cell (empty-compound-object) m))

(defn vector-compound-object
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

(defn internal-slot-key?
  [k]
  (or (= k slot-index-key)
      (= k reduce-index-key)
      (= k slot-declarations-key)
      (= k read-only-slots-key)
      (ids/node-id? k)
      (and (vector? k)
           (contains? #{slot-sync-key
                        reduce-sync-key
                        internal-metadata-prefix}
                      (first k)))))

(defn public-slot-keys
  [collection]
  (let [collection-net (compound-object collection)]
    (if (value/contradiction? collection-net)
      #{}
      (->> (keys (net/net-dict-or-empty collection-net))
           (remove internal-slot-key?)
           set))))

(defn read-only-slot?
  [collection-net slot-key]
  (contains? (get (net/net-dict-or-empty collection-net) read-only-slots-key #{})
             slot-key))

(defn read-only-slot-messages
  [parent-id collection-net slot-key]
  (let [slot-value (slot-strongest collection-net slot-key)]
    (if (value/unusable? slot-value)
      []
      [(message parent-id slot-value)])))
