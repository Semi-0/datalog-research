(ns propagators.cells.cell
  (:require [propagators.cells.value :as value]
            [propagators.ids :as ids]))

(defrecord Cell [name content strongest])

(defn cell?
  [x]
  (and (map? x)
       (contains? x :content)
       (contains? x :strongest)))

(defn cell
  ([content strongest]
   (cell :cell/anonymous content strongest))
  ([name content strongest]
   (map->Cell {:name name :content content :strongest strongest})))

(def make-cell cell)

(defn cell-name [c]
  (or (:name c) :cell/anonymous))

(defn cell-content [c]
  (:content c))

(defn cell-strongest
  "Strongest slot of a cell."
  [c]
  (:strongest c))

(defn construct-cell
  ([]
   (construct-cell (ids/new-node-id)))
  ([id]
   (fn [arg]
     (let [as-net (requiring-resolve 'propagators.network/as-net)
           assoc-net-node (requiring-resolve 'propagators.network/assoc-net-node)
           assoc-net-cell (requiring-resolve 'propagators.network/assoc-net-cell)
           blank-node (requiring-resolve 'propagators.graph/blank-node)
           net (as-net arg)
           n (-> net
                 (assoc-net-node id (blank-node))
                 (assoc-net-cell id (cell id value/nothing value/nothing)))]
       [id n])))
  ([id content strongest]
   (construct-cell id id content strongest))
  ([id name content strongest]
   (fn [arg]
     (let [as-net (requiring-resolve 'propagators.network/as-net)
           assoc-net-node (requiring-resolve 'propagators.network/assoc-net-node)
           assoc-net-cell (requiring-resolve 'propagators.network/assoc-net-cell)
           blank-node (requiring-resolve 'propagators.graph/blank-node)
           net (as-net arg)
           n (-> net
                 (assoc-net-node id (blank-node))
                 (assoc-net-cell id (cell name content strongest)))]
       [id n]))))
