(ns propagators.stdlib
  "Built-in propagator installers and network closure builders."
  (:require [propagators.network :refer [construct-cell empty-network
                                         primitive-propagator]]))

(def p:id (primitive-propagator (fn [x] x)))

(defn- install-prop
  "Run propagator installer `inst` on network `[graph env]`; return updated network."
  [[graph env] inst]
  (second (inst [graph env])))

;; this looks horrible i will fix it later
(defn bi-sync
  "Closure body: install boundary cells from snapshots and wire bi-directional `p:id`."
  [[graph env] input-snapshots output-snapshots]
  (let [[input-node in-cell] (first input-snapshots)
        [output-node out-cell] (first output-snapshots)
        input-id (:id input-node)
        output-id (:id output-node)]
    (-> [graph env]
        (construct-cell input-id [(:content in-cell) (:strongest in-cell)])
        second
        (construct-cell output-id [(:content out-cell) (:strongest out-cell)])
        second
        (install-prop (p:id [input-id output-id]))
        (install-prop (p:id [output-id input-id])))))

(def bi-sync-closure [bi-sync empty-network])
