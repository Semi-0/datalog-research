(ns propagators.stdlib.arithmetic
  (:require [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.stdlib.arithmetic.base :as base]
            [propagators.stdlib.arithmetic.provenance :as provenance]))

(defn procedure-extension
  "A named-network extension fragment for one layered procedure layer."
  [layer closure-value]
  (let [slot-id (new-node-id)]
    (-> net/empty-net
        (net/seed-net-cell slot-id closure-value closure-value)
        (net/assoc-net-dict-entry layer slot-id))))

(defn base-extension
  [closure-value]
  (procedure-extension :base closure-value))

(defn provenance-extension
  [closure-value]
  (procedure-extension :provenance closure-value))

(defn plus-base-extension
  []
  (base-extension base/plus-closure))

(defn plus-provenance-extension
  []
  (provenance-extension provenance/+))

(defn minus-base-extension
  []
  (base-extension base/minus-closure))

(defn minus-provenance-extension
  []
  (provenance-extension provenance/-))

(defn times-base-extension
  []
  (base-extension base/times-closure))

(defn times-provenance-extension
  []
  (provenance-extension provenance/*))

(defn divide-base-extension
  []
  (base-extension base/divide-closure))

(defn divide-provenance-extension
  []
  (provenance-extension provenance//))
