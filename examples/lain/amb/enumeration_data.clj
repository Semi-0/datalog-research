(ns examples.lain.amb.enumeration-data
  "Shared explicit snapshot transport for experimental all-answer observers."
  (:require [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.publication :as publication]
            [propagators.network-builder :as nb]))

(defn retained-set [network id]
  (let [v (observation/cell-base network id)]
    (cond
      (value/nothing? v) #{}
      (set? v) v
      :else (throw (ex-info "Enumeration expected a retained set" {:cell id :value v})))))

(defn seed-snapshot [network id v]
  (let [declared (nb/ensure-cell network id)
        emitted (publication/snapshot-message declared id v)
        [_ seeded] (core/eval-cell id (update emitted :value collection/content) declared)]
    seeded))
