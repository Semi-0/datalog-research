(ns propagators.cells.diff
  (:require [propagators.cells.merge :as merge]
            [propagators.message :refer [message]]
            [propagators.network :as net]))

;; we can use data abstraction to directly take things from network?
;; or maybe its more explicit we keep it low-level for now
(defn diff-cell [network-from network-to]
  (fn [node]
    (let [strongest-from (net/network-cell-strongest network-from node)
          strongest-to   (net/network-cell-strongest network-to node)]
      (when (merge/cell-updated? strongest-from strongest-to network-from)
        (message node strongest-from)))))

(defn diff-cells [nodes network-from network-to]
  (keep identity (map (diff-cell network-from network-to) nodes)))
