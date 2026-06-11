(ns propagators.datastructures.compound-object.cursor
  "Project compound-object slots into pure cursor values."
  (:require [propagators.cells.value :as value]
            [propagators.cursor :as cursor]
            [propagators.datastructures.compound-object.core :as core]
            [propagators.datastructures.compound-object.network-slot :as network-slot]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn- read-only-slots
  [source-net]
  (get (net/net-dict-or-empty source-net) core/read-only-slots-key #{}))

(defn- mappable-slot-key?
  [source-net slot-key]
  (not (contains? (read-only-slots source-net) slot-key)))

(defn- accessor-slot-value
  [source-net slot-key]
  (if (network-slot/source-slot-present? source-net slot-key)
    (network-slot/source-slot-value source-net slot-key)
    value/nothing))

(defn- accessor-slot-entry
  [source-id source-net slot-key]
  {:slot-key slot-key
   :path [slot-key]
   :source-id source-id
   :slot-value (accessor-slot-value source-net slot-key)})

(defn- compound-slot-entry
  [source-id source-net slot-key]
  {:slot-key slot-key
   :path [slot-key]
   :source-id source-id
   :slot-value (core/slot-value source-net slot-key)})

(defn- sorted-slot-keys
  [slot-keys]
  (vec (sort-by pr-str slot-keys)))

(defn- accessor-slot-entries
  [source-id source-net]
  (->> (network-slot/accessor-slot-keys source-net)
       (filter #(mappable-slot-key? source-net %))
       sorted-slot-keys
       (mapv #(accessor-slot-entry source-id source-net %))))

(defn- compound-slot-entries
  [source-id source-net]
  (->> (core/public-slot-keys source-net)
       (filter #(mappable-slot-key? source-net %))
       sorted-slot-keys
       (mapv #(compound-slot-entry source-id source-net %))))

(defn slot-cursor-value
  [source-id source-value]
  (cond
    (value/contradiction? source-value)
    value/contradiction

    (network-slot/accessor-network? source-value)
    (cursor/cursor (accessor-slot-entries source-id source-value))

    :else
    (let [source-net (core/compound-object source-value)]
      (if (value/contradiction? source-net)
        value/contradiction
        (cursor/cursor (compound-slot-entries source-id source-net))))))

(defn p:slot-cursor
  "Project a compound/accessor object to a pure finite cursor of slot entries."
  [source-id cursor-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     [(message cursor-id
               (slot-cursor-value source-id
                                  (net/network-cell-strongest network source-id)))])
   [source-id]
   [cursor-id]))
