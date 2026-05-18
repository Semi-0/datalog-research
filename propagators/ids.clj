(ns propagators.ids
  "Sortable, decentralized node ids (RFC-9562 UUID v7)."
  (:require [clj-uuid :as uuid]))

(defn new-node-id
  "New time-ordered UUID v7. Uses `v7nc` (fast); use `new-node-id-secure` if ids must be unguessable."
  []
  (uuid/v7nc))

(defn new-node-id-secure
  "Cryptographically random UUID v7 (slower than `new-node-id`)."
  []
  (uuid/v7))

(defn node-instant
  [id]
  (uuid/get-instant id))
