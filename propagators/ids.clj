(ns propagators.ids
  "Sortable, decentralized node ids (RFC-9562 UUID v7)."
  (:require [clj-uuid :as uuid]))

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn node-id? [x] (tagged? x :node-id))

(defn unwrap-node-id
  "UUID from `[:node-id uuid]`."
  [id]
  (when (node-id? id)
    (nth id 1)))

(defn new-node-id
  "New time-ordered node-id token `[:node-id <uuid-v7>]`. Uses `v7nc` (fast); use `new-node-id-secure` if ids must be unguessable."
  []
  [:node-id (uuid/v7nc)])

(defn new-node-id-secure
  "Cryptographically random node-id token (slower than `new-node-id`)."
  []
  [:node-id (uuid/v7)])

(defn node-instant
  [id]
  (uuid/get-instant (unwrap-node-id id)))
