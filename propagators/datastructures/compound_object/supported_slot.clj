(ns propagators.datastructures.compound-object.supported-slot
  "Live reads of explicitly supported accessor owners. Watchers reread ownership."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.merge :as owner]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.stdlib.premise-state :as state]))

(defn supported-owner? [network id]
  (let [content (net/network-cell-content network id)
        current (net/network-cell-strongest network id)]
    (or (collection/content? content)
        (and (membership/content? content)
             (datum/layer-present? current :base)
             (let [base (datum/layer-value current :base)]
               (or (owner/accessor-network? base)
                   (value/unusable? base)))))))

(defn- base [v]
  (if (datum/layer-present? v :base) (datum/layer-value v :base) v))

(defn- publication [parent field]
  (let [result {:base (base field)
                :support (support/combine (datum/support-of parent) (datum/support-of field))
                :premise-state (support/join (state/states-of parent) (state/states-of field))}]
    (if (or (datum/layer-present? parent :membership)
            (datum/layer-present? field :membership))
      (membership/content
       (assoc result :membership
              (support/combine (datum/membership-of parent) (datum/membership-of field))))
      (collection/content result))))

(defn- watch-id [slot target source reference]
  (ids/->NodeId
   (java.util.UUID/nameUUIDFromBytes
    (.getBytes (pr-str [::watch slot target source reference]) "UTF-8"))))

(defn activation [slot target source]
  (fn activate [_inputs _outputs network]
    (let [parent (net/network-cell-strongest network source)
          current (base parent)
          references (if (owner/accessor-network? current)
                       (->> (owner/accessor-parent-ids current slot)
                            (filter #(contains? (net/net-env network) %))
                            (remove #{target})
                            distinct vec)
                       [])
          source-field? (and (owner/accessor-network? current)
                             (owner/source-slot-present? current slot))
          fields (cond
                   (value/contradiction? current) [current]
                   (value/nothing? current) [value/nothing]
                   (owner/accessor-network? current)
                   (cond-> (mapv #(net/network-cell-strongest network %) references)
                     source-field? (conj (owner/source-slot-value current slot)))
                   (= :compiler-2/list-empty current) [value/nothing]
                   :else [value/contradiction])
          fields (if (seq fields) fields [value/nothing])]
      {:effects (mapv (fn [reference]
                        ((requiring-resolve 'propagators.gur/declare-prop)
                         (watch-id slot target source reference)
                         [::watch slot] [source reference] [target] activate))
                      references)
       :messages (mapv #(message target (publication parent %)) fields)})))
