(ns propagators.datastructures.compound_update
  "Compound-data update shape and predicates."
  (:require [meander.epsilon :as m]
            [propagators.ids :as id]))

(defrecord CompoundUpdate [head tail])

(defn compound-update
  [{:keys [head tail]}]
  (->CompoundUpdate head tail))

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

(defn complete-compound-data?
  "Both head and tail node ids are present."
  [x]
  (boolean
   (m/match x
     {:head (m/pred id/node-id?) :tail (m/pred id/node-id?)}
     true
     :else
     false)))

(defn compound-data-head?
  "Head-only compound update."
  [x]
  (and (not (contains? x :tail))
       (boolean
        (m/match x
          {:head (m/pred id/node-id?)}
          true
          :else
          false))))

(defn compound-data-tail?
  "Tail-only compound update."
  [x]
  (and (not (contains? x :head))
       (boolean
        (m/match x
          {:tail (m/pred id/node-id?)}
          true
          :else
          false))))

(defn partial-compound-data?
  "Head-only or tail-only compound slot (not complete)."
  [x]
  (or (compound-data-head? x) (compound-data-tail? x)))

(defn compound-data?
  [x]
  (or (complete-compound-data? x)
      (partial-compound-data? x)
      (compound-data-head? x)
      (compound-data-tail? x)))
