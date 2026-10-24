(ns propagators.datastructures.compound-information
  "Direct slot access and semantic classification for built-in information values."
  (:require
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.support-collection :as supported]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.datastructures.tms :as tms]))

(defn slot-data
  [value key]
  (obj/slot-value value key))

(defn select-slot-data
  [value keys]
  (into {}
        (map (fn [key] [key (slot-data value key)]))
        keys))

(defn semantic-kind
  [value]
  (cond
    (membership/content? value) :membership-collection
    (supported/content? value) :support-collection
    (tms/distributed-value? value) :distributed-tms
    :else nil))
