(ns propagators.reality
  "Reality boundary propagators built on evaluator IO."
  (:require [propagators.io :as io]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn p:reality-in
  "Consume matching IO inbox records and deliver their messages to `cell-id`."
  [io-id cell-id]
  (fn [network]
    (let [[prop-id n]
          ((prop/construct-propagator
            (fn [_inputs _outputs n]
              (let [records (io/matching-inbox-records n io-id cell-id)]
                (into (mapv :message records)
                      [(io/io-delivery :drain-inbox-records records)])))
            []
            [cell-id])
           network)]
      [prop-id
       (-> n
           (net/assoc-net-dict-entry [:reality/in-route io-id cell-id] prop-id)
           (net/assoc-net-dict-entry [:reality/in-cell io-id] cell-id))])))

(defn p:reality-out
  "Publish `cell-id`'s strongest value as a formal IO outbox record."
  [io-id cell-id]
  (fn [network]
    (let [[prop-id n]
          ((prop/construct-propagator
            (fn [_inputs _outputs n]
              (let [v (net/network-cell-strongest n cell-id)]
                [(io/io-delivery :append-outbox
                                 (io/io-record io-id cell-id (message/message cell-id v)))]))
            [cell-id]
            [])
           network)]
      [prop-id
       (net/assoc-net-dict-entry n [:reality/out-cell io-id] cell-id)])))

(defn inject-input
  "Add one IO input record and enqueue the matching `p:reality-in` route."
  [n io-id cell-id value]
  (let [record (io/io-record io-id cell-id (message/message cell-id value))
        n' (io/add-inbox-record n record)]
    (if-let [prop-id (net/network-dict-entry n' [:reality/in-route io-id cell-id])]
      (io/enqueue-delivery n' (io/prop-delivery prop-id))
      n')))
