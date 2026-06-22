(ns propagators.gur.subenv.output
  "Child-to-parent output projection for lexical sub-env frames."
  (:require [propagators.cells.diff :as diff]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur.subenv.queue :as queue]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(declare externalize-output-value)

(defn- slot-value-rank
  [v]
  (if (and (net/net? v) (obj/accessor-network? v))
    (+ (* 10 (count (obj/accessor-source-slots v)))
       (count (obj/accessor-slot-keys v)))
    100))

(defn- accessor-slot-parent-value
  [child-net accessor-value slot-key]
  (->> (obj/accessor-parent-ids accessor-value slot-key)
       (filter #(contains? (net/net-env child-net) %))
       (sort-by pr-str)
       (keep (fn [parent-id]
               (let [v (net/network-cell-strongest child-net parent-id)]
                 (when-not (value/unusable? v)
                   v))))
       (sort-by (juxt slot-value-rank pr-str))
       last))

(defn- externalize-accessor-value
  [child-net accessor-value]
  (let [slots (obj/accessor-slot-keys accessor-value)
        source-slots
        (reduce
         (fn [acc slot-key]
           (let [parent-v (accessor-slot-parent-value child-net
                                                       accessor-value
                                                       slot-key)
                 v (if (or (nil? parent-v)
                           (value/unusable? parent-v))
                     (if (obj/accessor-source-slot-present? accessor-value slot-key)
                       (obj/accessor-source-slot-value accessor-value slot-key)
                       parent-v)
                     parent-v)]
             (if (or (nil? v) (value/unusable? v))
               acc
               (assoc acc slot-key (externalize-output-value child-net v)))))
         {}
         slots)]
    (if (empty? source-slots)
      accessor-value
      (obj/as-accessor-network source-slots))))

(defn externalize-output-value
  [child-net v]
  (if (and (net/net? v) (obj/accessor-network? v))
    (externalize-accessor-value child-net v)
    v))

(defn externalize-output-cells
  [child-net external-output-ids]
  (reduce
   (fn [n external-id]
     (if-let [inner-id (net/lookup-inner-out n external-id)]
       (let [v (net/network-cell-strongest n inner-id)
             v* (externalize-output-value n v)]
         (if (= v v*)
           n
           (nb/seed-cell n inner-id v*)))
       n))
   child-net
   external-output-ids))

(defn p:run-subenv-frame
  [owner-id external-output-ids]
  (let [external-output-ids (vec external-output-ids)]
    (prop/construct-propagator
     (fn [_inputs _outputs parent-net]
       (let [child0 (net/network-cell-strongest parent-net owner-id)]
         (if-not (net/net? child0)
           []
           (let [child1 (queue/run-child-queue child0)
                 diff-view (externalize-output-cells child1 external-output-ids)
                 external-msgs (queue/external-messages child1)
                 child2 (-> child1
                            queue/clear-child-queue
                            queue/clear-external-messages)
                 output-msgs (vec (diff/diff-internal-output-cells
                                    diff-view
                                    parent-net
                                    external-output-ids))]
             (cond-> (vec (concat output-msgs external-msgs))
               (not= child0 child2)
               (conj (message owner-id
                              child2)))))))
     [owner-id]
     (into [owner-id] external-output-ids))))
