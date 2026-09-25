(ns propagators.relationship
  "Persistent parent/child relationships between path-qualified network nodes."
  (:refer-clojure :exclude [parents])
  (:require [propagators.graph :as graph]))

(def empty-relationship {})

(defn node-key
  [network-path node-id]
  [network-path node-id])

(defn relate
  [relationship parent child]
  (-> relationship
      (graph/ensure-node parent)
      (graph/ensure-node child)
      (graph/link-edge parent child)))

(defn children
  [relationship parent]
  (if-let [node (get relationship parent)]
    (graph/node-output-ids node)
    #{}))

(defn parents
  [relationship child]
  (if-let [node (get relationship child)]
    (graph/node-input-ids node)
    #{}))

(defn- merge-node
  [left right]
  (graph/node (into (graph/node-input-ids left)
                    (graph/node-input-ids right))
              (into (graph/node-output-ids left)
                    (graph/node-output-ids right))))

(defn merge-relationships
  ([] empty-relationship)
  ([relationship] relationship)
  ([left right]
   (merge-with merge-node left right))
  ([left right & more]
   (reduce merge-relationships
           (merge-relationships left right)
           more)))

(defn project
  "Keep only relationships whose parent and child are both in `node-keys`."
  [relationship node-keys]
  (let [kept (set node-keys)]
    (reduce-kv
     (fn [projected parent node]
       (if (contains? kept parent)
         (reduce
          (fn [current child]
            (if (contains? kept child)
              (relate current parent child)
              current))
          projected
          (graph/node-output-ids node))
         projected))
     empty-relationship
     relationship)))
