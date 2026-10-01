(ns propagators.experimental.visualization.ttms
  "Explicit TTMS transport at the experimental collection boundary."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.network :as net]
            [propagators.stdlib.premise-state :as state]))

(defn dependency-datum
  "A publication's identity names its immutable wrapper slots, not its payload.
  Preserve the complete raw value without serializing a nested network into IDs.
  Different TTMS observations remain distinct by their base and support values."
  [identity payload sources]
  (let [wrapper (dependency/dependency-value identity sources)
        base-id (get (net/net-dict-or-empty wrapper) :base)]
    (net/assoc-net-cell wrapper base-id (cell/cell payload payload))))

(defn supported? [v]
  (or (collection/content? v)
      (and (datum/layer-present? v :base)
           (datum/layer-present? v :support)
           (support/support? (datum/support-of v)))))

(defn projection [v]
  (if (collection/content? v)
    (merge/strongest-value v net/empty-net)
    v))

(defn payload [v]
  (if (supported? v)
    (datum/layer-value (projection v) :base)
    v))

(defn status [v]
  (let [current (projection v)
        base (payload current)]
    (cond
      (value/contradiction? base) :contradiction
      (and (value/unusable? current)
           (some #(= :retracted (:premises-status %)) (state/states-of current))) :withdrawn
      (value/unusable? current) :pending
      :else :ready)))

(defn state-update [contents]
  (collection/content
   {:premise-state (apply support/join
                          (map #(state/states-of (projection %)) contents))}))

(defn publication
  "Derived output. Never create source epochs or turn unrelated state into support."
  [result contents]
  (let [inputs (mapv projection contents)
        premises (apply support/combine (map datum/support-of inputs))
        states (state-update contents)]
    (if (or (value/unusable? result)
            (some value/unusable? inputs)
            (support/unusable? premises))
      states
      (collection/merge-content states
       (collection/content {:base result :support premises})))))
