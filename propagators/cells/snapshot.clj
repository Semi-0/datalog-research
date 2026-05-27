(ns propagators.cells.snapshot
  (:require [propagators.graph :refer [get-node node-output-ids]]))

(defrecord Snapshot [id cell])

(defn snap?
  [x]
  (and (map? x) (contains? x :id) (contains? x :cell)))

(defn snap [node-id cell]
  (->Snapshot node-id cell))

(defn snap-id [s] (:id s))
(defn snap-cell [s] (:cell s))

(defn cell-snapshot [env]
  (fn [node-id]
    (snap node-id (get env node-id))))

(defn pop-inputs [node-ids graph]
  (mapcat (fn [id]
            (when (contains? graph id)
              (node-output-ids (get-node graph id))))
          node-ids))

(defn take-cells [node-ids env _graph]
  (let [snap-fn (cell-snapshot env)]
    (mapv snap-fn node-ids)))

(defn snapshot-for-id [node-id snapshots]
  (some (fn [s]
          (when (= node-id (snap-id s)) s))
        snapshots))
