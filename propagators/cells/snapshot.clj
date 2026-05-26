(ns propagators.cells.snapshot
  (:require [propagators.graph :refer [get-node node-output-ids]]
            [propagators.helpers.tagged :refer [tagged?]]))

(def snap? (tagged? :snap))
(defn snap [node-id cell] [:snap node-id cell])
(defn snap-id [s] (nth s 1))
(defn snap-cell [s] (nth s 2))

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
