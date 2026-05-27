(ns propagators.ids
  "Sortable, decentralized node ids (RFC-9562 UUID v7)."
  (:require [clj-uuid :as uuid]))

(defrecord NodeId [uuid])

(defn node-id?
  [x]
  (and (map? x)
       (contains? x :uuid)
       (instance? java.util.UUID (:uuid x))))

(defn unwrap-node-id
  "UUID from node id value."
  [id]
  (when (node-id? id)
    (:uuid id)))

(defn new-node-id
  "New time-ordered node id."
  []
  (->NodeId (uuid/v7nc)))

(defn new-node-id-secure
  "Cryptographically random node id."
  []
  (->NodeId (uuid/v7)))

(defn node-instant
  [id]
  (uuid/get-instant (unwrap-node-id id)))
