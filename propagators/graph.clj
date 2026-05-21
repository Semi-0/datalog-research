(ns propagators.graph
  "Immutable directed graph: `[:node-id …] → [:node inputs outputs]`."
  (:require [propagators.ids :as ids]))

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn node? [x] (tagged? x :node))
(defn node [inputs outputs] [:node (set inputs) (set outputs)])
(defn blank-node [] (node #{} #{}))
(def empty-graph {})
(defn graph? [x] (map? x))

(defn node-id
  "Node-id token `[:node-id …]` (env/graph key)."
  [x]
  (if (ids/node-id? x)
    x
    (throw (ex-info "expected node-id token" {:x x}))))

(defn node-input-ids [n] (nth n 1))
(defn node-output-ids [n] (nth n 2))

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
