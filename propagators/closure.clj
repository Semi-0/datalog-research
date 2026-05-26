(ns propagators.closure
  (:require [propagators.boundary :as boundary]
            [propagators.cells.diff :refer [diff-internal-output-cells]]
            [propagators.cells.value :as value]
            [propagators.network :refer [network-cell-strongest
                                         clear-dict inner-ids-in inner-ids-out]]
            [propagators.helpers.tagged :refer [tagged?]]
            ))

(def closure? (tagged? :closure))
;; [:closure f net] or [:closure f net boundary-cache-map]
(defn closure [f n] [:closure f n])
(defn closure-f [c] (nth c 1))
(defn closure-net [c] (nth c 2))

(defn closure-boundary
  "Optional avatar map on a closure value: {:ins :outs :in-avatars :out-avatars :f}."
  [c]
  (when (< 3 (count c)) (nth c 3)))

(defn apply-network-closure [[f inner-net] external-network]
  (f inner-net (vec (inner-ids-in external-network)) (vec (inner-ids-out external-network)) external-network))

(defn- boundary-nodes [closure-cell-id nodes]
  (vec (remove #(= closure-cell-id %) nodes)))

(def create-boundary-outputs boundary/create-boundary-outputs)
(def create-boundary-inputs boundary/create-boundary-inputs)

(defn compound-activate
  "Compound propagator body. `closure-in-id` holds `[:closure f net]`; boundary cells are the other ports."
  [closure-in-id]
  (fn [input-ids output-ids network]
    (let [closure-cv (network-cell-strongest network closure-in-id)
          closure-payload (value/value-payload closure-cv)
          ins (boundary-nodes closure-in-id input-ids)
          outs (vec output-ids)
          in-vals (mapv #(network-cell-strongest network %) ins)]
      (if (or (value/unusable? closure-cv)
              (value/any-unusable-values? in-vals)
              (nil? closure-payload))
        []
        (let [net* (-> network
                       (create-boundary-outputs outs)
                       (create-boundary-inputs ins))
              net' (apply-network-closure closure-payload net*)
              net'' (boundary/run-internal-network ins net')]
          (diff-internal-output-cells net'' network outs))))))
