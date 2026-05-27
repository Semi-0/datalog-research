(ns propagators.datastructures.compound_update
  "Compound-data update shape and predicates."
  (:require [propagators.ids :as id]
            [propagators.network :as net]))

(defrecord CompoundUpdate [head tail])
(defrecord CompoundSync [subnet slots])

(defn compound-update
  [{:keys [head tail]}]
  (->CompoundUpdate head tail))

(defn compound-sync
  ([subnet]
   (compound-sync subnet []))
  ([subnet slots]
   (->CompoundSync subnet (vec slots))))

(defn sync-subnet [sync]
  (:subnet sync))

(defn sync-slots [sync]
  (vec (:slots sync)))

(defn update-head [update]
  (:head update))

(defn update-tail [update]
  (:tail update))

(defn take-head [update]
  (when-let [h (update-head update)]
    (compound-update {:head h})))

(defn take-tail [update]
  (when-let [t (update-tail update)]
    (compound-update {:tail t})))

(defn compound-id
  "Extract the single node-id referenced by a head-only or tail-only update map."
  [update]
  (or (update-head update)
      (update-tail update)))

(defn compound-data-ids
  "All node-ids referenced by a compound-data update."
  [update]
  (vec (keep identity [(update-head update) (update-tail update)])))

(defn compound-sync?
  [x]
  (instance? CompoundSync x))

(defn complete-compound-data?
  "Both head and tail node ids are present."
  [x]
  (and (instance? CompoundUpdate x)
       (id/node-id? (:head x))
       (id/node-id? (:tail x))))

(defn compound-data-head?
  "Head-only compound update."
  [x]
  (and (instance? CompoundUpdate x)
       (id/node-id? (:head x))
       (nil? (:tail x))))

(defn compound-data-tail?
  "Tail-only compound update."
  [x]
  (and (instance? CompoundUpdate x)
       (id/node-id? (:tail x))
       (nil? (:head x))))

(defn partial-compound-data?
  "Head-only or tail-only compound slot (not complete)."
  [x]
  (or (compound-data-head? x) (compound-data-tail? x)))

(defn compound-data?
  [x]
  (instance? CompoundUpdate x))
