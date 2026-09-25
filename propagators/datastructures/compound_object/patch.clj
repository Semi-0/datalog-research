(ns propagators.datastructures.compound-object.patch
  "Explicit declaration patches emitted by compound accessor propagators."
  (:require [propagators.cell-evaluator :as cell-evaluator]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.relationship :as relationship]))

(def accessor-declaration-op :compound/accessor-declaration)

(defn accessor-declaration
  [collection-id value child-ids]
  {:op accessor-declaration-op
   :id collection-id
   :value value
   :child-ids (vec child-ids)})

(defn accessor-patch?
  [_emitter patch _network]
  (= accessor-declaration-op (:op patch)))

(defn apply-accessor-patch
  [emitter patch network]
  (let [collection-id (message/message-id patch)
        [tasks updated-network]
        (cell-evaluator/evaluate patch network)
        updated
        (reduce
         (fn [current child-id]
           (net/update-net-relationship
            current
            relationship/relate
            emitter
            (relationship/node-key
             [:outer [:cell collection-id]]
             child-id)))
         updated-network
         (:child-ids patch))]
    [tasks updated]))
