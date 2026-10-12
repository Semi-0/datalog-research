(ns propagators.experimental.amb.publication
  "Reuse source stamping and message lifting for explicit search cell snapshots."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.premise-state :as state]))

(defn next-source [source previous base status]
  (let [previous (if (datum/layer-present? previous :premise-state)
                   {:base (datum/layer-value previous :base)
                    :support (into #{} (filter #(= source (:source %)))
                                   (state/states-of previous))}
                   previous)
        result (publication/next-source-datum source previous base status)]
    (assoc result :premise-state (:support result))))

(defn source-message [network source base status]
  (message/message source
                   (next-source source (net/network-cell-strongest network source) base status)))

(defn snapshot-message [network source base]
  (source-message network source base :active))

(defn lift [activation]
  (prop/compose-activation activation (message/lift-message collection/content)))

(defn choice-messages [network record output control]
  (let [base (if (= :active (:status record))
               (nth (:domain record) (:index record))
               value/nothing)
        premise {:source output :timestamp (:timestamp record)
                 :premises-status (:status record)}]
    [(message/message output {:base base :support #{premise} :premise-state #{premise}})
     (snapshot-message network control record)]))
