(ns propagators.graph
  "Immutable directed graph: node-id -> node."
  (:require [propagators.ids :as ids]))

(defrecord Node [inputs outputs])

(defn node?
  [x]
  (and (map? x)
       (contains? x :inputs)
       (contains? x :outputs)
       (set? (:inputs x))
       (set? (:outputs x))))

(defn node [inputs outputs]
  (->Node (set inputs) (set outputs)))
(defn blank-node [] (node #{} #{}))
(def empty-graph {})
(defn graph? [x] (map? x))

(defn node-id
  "Node id token used as env/graph key."
  [x]
  (if (ids/node-id? x)
    x
    (throw (ex-info "expected node-id token" {:x x}))))

(defn node-input-ids [n] (:inputs n))
(defn node-output-ids [n] (:outputs n))

(defn get-node [graph id]
  (or (get graph id)
      (throw (ex-info "unknown node" {:id id :known (keys graph)}))))

(defn assoc-graph [graph id node] (assoc graph id node))

(defn node-inputs
  "Input node-id tokens for `node`."
  [_graph node]
  (node-input-ids node))

(defn node-outputs
  "Output node-id tokens for `node`."
  [_graph node]
  (node-output-ids node))

(defn link-edge [graph from-id to-id]
  (let [from (get-node graph from-id)
        to (get-node graph to-id)]
    (-> graph
        (assoc-graph from-id
                     (node (node-input-ids from)
                           (conj (node-output-ids from) to-id)))
        (assoc-graph to-id
                     (node (conj (node-input-ids to) from-id)
                           (node-output-ids to))))))
