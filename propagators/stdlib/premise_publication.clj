(ns propagators.stdlib.premise-publication
  "Publish current observations and independent premise states. No epoch creation."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.datastructures.support :as support]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.stdlib.premise-state :as state]))

(defn- legacy-content [result]
  (let [states (state/states-of result)
        state-update (collection/content {:premise-state states})]
    (if (and (datum/layer-present? result :base)
             (not (value/unusable? result)))
      (collection/merge-content
       state-update
       (collection/content {:base (datum/layer-value result :base)
                            :support (datum/support-of result)}))
      state-update)))

(defn content [result]
  (if (datum/layer-present? result :membership)
    (let [states (state/states-of result)]
      (if (value/unusable? result)
        (membership/content {:premise-state states})
        (membership/content {:base (datum/layer-value result :base)
                             :support (datum/support-of result)
                             :membership (datum/membership-of result)
                             :premise-state states})))
    (legacy-content result)))

(defn transport-states
  "All watched inputs contribute state knowledge, not output support."
  [patches inputs outputs network]
  (let [arguments (mapv #(net/network-cell-strongest network %) inputs)]
    (if (some #(datum/layer-present? % :premise-state) arguments)
      (let [states (apply support/join (map state/states-of arguments))
            update ((if (some #(datum/layer-present? % :membership) arguments)
                      membership/content collection/content) {:premise-state states})]
        (into (vec patches) (map #(message/message % update) outputs)))
      patches)))
