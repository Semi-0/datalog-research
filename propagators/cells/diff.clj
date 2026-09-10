(ns propagators.cells.diff
  (:require [propagators.cells.merge :as merge]
            [propagators.message :refer [message]]
            [propagators.network :as net]))

;; we can use data abstraction to directly take things from network?
;; or maybe its more explicit we keep it low-level for now
(defn diff-cell [network-from network-to]
  (fn [nodeA nodeB]
    (let [content-from (net/network-cell-content network-from nodeA)
          content-to (net/network-cell-content network-to nodeB)
          strongest-from (net/network-cell-strongest network-from nodeA)
          strongest-to (net/network-cell-strongest network-to nodeB)]
      (cond
        (not= content-from content-to)
        (message nodeB content-from)

        (merge/cell-updated? strongest-from strongest-to network-to)
        (message nodeB strongest-from)

        :else
        nil))))

(defn diff-cells [nodesA nodesB network-from network-to]
  (keep identity (map (diff-cell network-from network-to) nodesA nodesB)))

(defn diff-internal-output-cells
  "Diff inner→outer for each `external-output` based on `network-from`'s `net-dict` mapping."
  [network-from network-to external-outputs]
  (keep identity
        (mapcat (fn [ext]
                  (when-let [int-id (net/lookup-inner-out network-from ext)]
                    (diff-cells [int-id] [ext] network-from network-to)))
                (vec external-outputs))))
