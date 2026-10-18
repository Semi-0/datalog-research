(ns propagators.experimental.amb.learning-view
  "Display-only, slot-backed projection of solver-owned no-good knowledge."
  (:require [propagators.datastructures.compound-object :as object]
            [propagators.cells.cell :as cell]
            [propagators.network :as net]
            [propagators.experimental.amb.contracts :as contracts]))

(def type-slot :amb.view/type)
(def knowledge-slot :amb.view/knowledge)
(def view-type :amb/no-goods)

(def ^:private template
  (object/compound-object {type-slot view-type knowledge-slot #{}}))

(defn learning-view [knowledge]
  (let [knowledge (contracts/checked ::contracts/knowledge knowledge)]
    (net/assoc-net-cell template (object/slot-cell-id template knowledge-slot)
                        (cell/cell knowledge knowledge))))

;; These public slots are the presentation contract. Reading them never runs search.
(defn learning-view? [value]
  (= view-type (object/slot-value value type-slot)))

(defn knowledge [value]
  (contracts/checked ::contracts/knowledge
                     (object/slot-value value knowledge-slot)))
