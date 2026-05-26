(ns propagators.closure
  (:require [propagators.cells.diff :refer [diff-cells]]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.cells.value :as value]
            [propagators.core :refer [run-tasks]]
            [propagators.network :refer [net-graph
                                         network-cell-strongest network-cell-content construct-cell]]
            [propagators.ids :as id]
            [propagators.helpers.tagged :refer [tagged?]]
            [propagators.stdlib :as stdlib]))

(def closure? (tagged? :closure))
;; [:closure f net] or [:closure f net boundary-cache-map]
(defn closure [f n] [:closure f n])
(defn closure-f [c] (nth c 1))
(defn closure-net [c] (nth c 2))

(defn closure-boundary
  "Optional avatar map on a closure value: {:ins :outs :in-avatars :out-avatars :f}."
  [c]
  (when (< 3 (count c)) (nth c 3)))

(defn apply-network-closure [[f inner-net] input-nodes output-nodes external-network]
  (f inner-net input-nodes output-nodes external-network))

(defn- boundary-nodes [closure-cell-id nodes]
  (vec (remove #(= closure-cell-id %) nodes)))

(defn create-boundary-cells
  [link-fn]
  (fn [net ids]
    (let [ids (vec ids)]
      (loop [ids-to-do ids
             processed-net net
             avatar-ids []]
        (if (empty? ids-to-do)
          [(vec avatar-ids) processed-net]
          (let [head (first ids-to-do)
                [id* net*] ((construct-cell
                             (id/new-node-id)
                             (network-cell-strongest net head)
                             (network-cell-content net head))
                            processed-net)
                net** (link-fn net* head id*)]
            (recur (rest ids-to-do) net** (conj avatar-ids id*))))))))

(def create-boundary-outputs (create-boundary-cells stdlib/nothing-out-link))
(def create-boundary-inputs (create-boundary-cells stdlib/nothing-in-link))

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
        (let [[boundary-outputs net*] (create-boundary-outputs network outs)
              [boundary-inputs net**] (create-boundary-inputs net* ins)
              net' (apply-network-closure closure-payload
                                          boundary-inputs boundary-outputs net**)
              net'' (run-tasks (pop-inputs boundary-inputs (net-graph net')) net')]
          (diff-cells boundary-outputs outs net'' network))))))
