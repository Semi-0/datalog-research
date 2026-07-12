(ns propagators.datastructures.compound-information
  "Direct slot access and semantic classification for built-in information values."
  (:require [propagators.datastructures.behavior :as behavior]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.event :as event]
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
    (or (event/event-content? value)
        (event/event-fact? value)
        (event/event-projection? value)) :event-content
    (or (behavior/behavior-content? value)
        (behavior/behavior-value? value)) :behavior-content
    (tms/distributed-value? value) :distributed-tms
    :else nil))
