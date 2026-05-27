(ns propagators.message)

(defrecord Message [id value])

(defn message?
  [x]
  (and (map? x)
       (contains? x :id)
       (contains? x :value)))

(defn message [node-id cell-value]
  (->Message node-id cell-value))

(defn message-id [m] (:id m))
(defn message-value [m] (:value m))
