(ns propagators.closure
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.diff :refer [diff-cells]]
            [propagators.cells.snapshot :refer [pop-inputs snap-cell snap-id snapshot-for-id take-cells]]
            [propagators.cells.value :as value]
            [propagators.core :refer [run-tasks]]
            [propagators.network :refer [construct-propagator net-graph network-cell-strongest network-cell-content construct-cell]]
            [propagators.graph :as g]
            [propagators.message :as m]
            [propagators.ids :as id]
            [propagators.stdlib :as stdlib]
            ))

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn closure? [x] (tagged? x :closure))
;; closure should have network inputs outputs and environment at that timepoint
(defn closure [f n] [:closure f n])
(defn closure-f [c] (nth c 1))
(defn closure-net [c] (nth c 2))

(defn apply-network-closure [[f inner-net] input-nodes output-nodes external-network]
  (f inner-net input-nodes output-nodes external-network))

(defn closure-payload [snap]
  (value/value-payload (cell/cell-strongest (snap-cell snap))))

(defn- boundary-nodes [closure-cell-id nodes]
  (vec (remove #(= closure-cell-id %) nodes)))


(defn create-boundary-cells
  [make-boundary]
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
              [_ net**] ((make-boundary [head id*]) net*)]
          (recur (rest ids-to-do) net** (conj avatar-ids id*))))))))

(def create-boundary-outputs (create-boundary-cells (fn [[real avatar]] (stdlib/p:nothing [avatar real]))))
(def create-boundary-inputs (create-boundary-cells (fn [[real avatar]] (stdlib/p:nothing [real avatar]))))


(defn compound-activate [closure-in-id closure-out-id]
  (fn [input-ids output-ids network]
    (let [closure-cv (network-cell-strongest network closure-in-id)
          closure-payload (value/value-payload closure-cv)
          ins (boundary-nodes closure-in-id input-ids)
          outs (boundary-nodes closure-out-id output-ids)
          ;; we copy the output cell with new id in the env of network
          ;; then connect the network with p:nothing to maintain topology coherency
          [boundary-outputs net*] (create-boundary-outputs network outs)
          [boundary-inputs net*] (create-boundary-inputs net* ins)
          in-vals (mapv #(network-cell-strongest net* %) ins)]
      (if (or (value/unusable? closure-cv)
              (value/any-unusable-values? in-vals)
              (nil? closure-payload))
        []
        (let [cf (closure-f closure-payload)
              net' (apply-network-closure closure-payload boundary-inputs boundary-outputs net*)
              net'' (run-tasks (pop-inputs boundary-inputs (net-graph net')) net')
              ;; pop inputs would create the inputs inself
              closure-struct' (closure cf net'')]
          (into (diff-cells boundary-outputs outs net'' network)
                [(m/message closure-out-id closure-struct')]))))))

(defn compound-propagator
  [closure-in closure-out inputs outputs]
  (construct-propagator (compound-activate closure-in closure-out)
                        (into [closure-in] inputs)
                        (into [closure-out] outputs)))
