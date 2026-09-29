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

(defn lift-message
  "Lift a value transform into compose-activation's result-transform contract.
  Destinations, message metadata, patch ordering, and other patches are preserved."
  [f]
  (fn [patches _inputs _outputs _network]
    (mapv (fn [patch]
            (if (message? patch)
              (update patch :value f)
              patch))
          patches)))
