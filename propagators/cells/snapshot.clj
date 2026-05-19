(ns propagators.cells.snapshot
  (:require [propagators.graph :refer [get-node node-id node-outputs]]))

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn snap? [x] (tagged? x :snap))
(defn snap [node-id cell] [:snap node-id cell])
(defn snap-id [s] (nth s 1))
(defn snap-cell [s] (nth s 2))

(defn cell-snapshot [env]
  (fn [node]
    (snap (node-id node) (get env (node-id node)))))

(defn pop-inputs [snapshots graph]
  (mapcat (fn [s]
            (let [n (get-node graph (snap-id s))]
              (node-outputs graph n)))
          snapshots))

(defn take-cells [node-ids env graph]
  (let [snap-fn (cell-snapshot env)]
    (map (fn [id] (snap-fn (get-node graph id))) node-ids)))

(defn snapshot-for-id [node-id snapshots]
  (some (fn [s]
          (when (= node-id (snap-id s)) s))
        snapshots))
