(ns propagators.closure
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.diff :refer [diff-cells]]
            [propagators.cells.snapshot :refer [pop-inputs snap-cell snap-id snapshot-for-id take-cells]]
            [propagators.cells.value :as value]
            [propagators.core :refer [run-tasks]]
            [propagators.network :refer [assoc-net-cell net-graph
                                         network-cell-strongest network-cell-content construct-cell]]
            [propagators.graph :as g]
            [propagators.message :as m]
            [propagators.ids :as id]
            [propagators.stdlib :as stdlib]
            ))

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn closure? [x] (tagged? x :closure))
;; [:closure f net] or [:closure f net boundary-cache-map]
(defn closure [f n] [:closure f n])
(defn closure-f [c] (nth c 1))
(defn closure-net [c] (nth c 2))

(defn closure-boundary
  "Optional persisted avatar map: {:ins :outs :in-avatars :out-avatars :f}."
  [c]
  (when (< 3 (count c)) (nth c 3)))

(defn closure-with-boundary [f net boundary]
  (if boundary
    [:closure f net boundary]
    [:closure f net]))

(defn apply-network-closure [[f inner-net] input-nodes output-nodes external-network]
  (f inner-net input-nodes output-nodes external-network))

(defn closure-payload [snap]
  (value/value-payload (cell/cell-strongest (snap-cell snap))))

(defn- boundary-nodes [closure-cell-id nodes]
  (vec (remove #(= closure-cell-id %) nodes)))

(defn- payload->closure [cv]
  (when-let [p (value/value-payload cv)]
    (when (closure? p) p)))

(defn- boundary-cache-hit? [cf ins outs cached]
  (and cached
       (= cf (:f cached))
       (= (vec ins) (vec (:ins cached)))
       (= (vec outs) (vec (:outs cached)))))

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

(defn- refresh-boundaries
  "Sync avatar strongest/content from real cells (reuse existing avatar ids)."
  [net ins outs in-avatars out-avatars]
  (reduce (fn [n [real avatar]]
            (assoc-net-cell n avatar
                            (cell/cell (network-cell-content net real)
                                       (network-cell-strongest net real))))
          (reduce (fn [n [real avatar]]
                    (assoc-net-cell n avatar
                                    (cell/cell (network-cell-content net real)
                                               (network-cell-strongest net real))))
                  net
                  (map vector ins in-avatars))
          (map vector outs out-avatars)))

(defn- ensure-boundaries
  "Strategy A: reuse avatar topology from closure-out when (f, ins, outs) unchanged."
  [network ins outs cf closure-out-id]
  (let [cached (some-> (network-cell-strongest network closure-out-id)
                       payload->closure
                       closure-boundary)]
    (if (boundary-cache-hit? cf ins outs cached)
      {:cache-hit? true
       :boundary-inputs (:in-avatars cached)
       :boundary-outputs (:out-avatars cached)
       :net (refresh-boundaries network ins outs (:in-avatars cached) (:out-avatars cached))
       :boundary cached}
      (let [[boundary-outputs net*] (create-boundary-outputs network outs)
            [boundary-inputs net**] (create-boundary-inputs net* ins)]
        {:cache-hit? false
         :boundary-inputs boundary-inputs
         :boundary-outputs boundary-outputs
         :net net**
         :boundary {:ins ins :outs outs
                    :in-avatars boundary-inputs :out-avatars boundary-outputs
                    :f cf}}))))

(defn compound-activate [closure-in-id closure-out-id]
  (fn [input-ids output-ids network]
    (let [closure-cv (network-cell-strongest network closure-in-id)
          closure-payload (value/value-payload closure-cv)
          ins (boundary-nodes closure-in-id input-ids)
          outs (boundary-nodes closure-out-id output-ids)
          in-vals (mapv #(network-cell-strongest network %) ins)]
      (if (or (value/unusable? closure-cv)
              (value/any-unusable-values? in-vals)
              (nil? closure-payload))
        []
        (let [cf (closure-f closure-payload)
              {:keys [cache-hit? boundary-inputs boundary-outputs net boundary]}
              (ensure-boundaries network ins outs cf closure-out-id)
              net' (if cache-hit?
                     net
                     (apply-network-closure closure-payload
                                            boundary-inputs boundary-outputs net))
              net'' (run-tasks (pop-inputs boundary-inputs (net-graph net')) net')
              closure-struct' (closure-with-boundary cf net'' boundary)]
          (into (diff-cells boundary-outputs outs net'' network)
                [(m/message closure-out-id closure-struct')]))))))
