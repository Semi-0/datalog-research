(ns propagators.experimental.visualization.selection
  "Ordered selection facts in a reducer cell; no source-data mutation."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.experimental.visualization.collections :as collections]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- template [tag keys compute]
  (let [ids (mapv #(collections/stable-id ::selection tag %) keys)
        out (collections/stable-id ::selection tag :out)
        initial (reduce nb/ensure-cell net/empty-net (conj ids out))
        [_ installed] ((prop/construct-propagator
                        (collections/stable-id ::selection tag :compute) tag
                        (fn [_ _ network]
                          [(message out (apply compute
                                               (map #(net/network-cell-strongest network %) ids)))])
                        ids [out]) initial)]
    (reduce (fn [network [key id]] (net/assoc-net-dict-entry network key id))
            installed (conj (mapv vector keys ids) [:out out]))))

(def merge-template
  (template ::merge [:content :update]
            (fn [content update]
              (reduce-kv (fn [slots k v]
                           (if (and (contains? slots k) (not= (get slots k) v))
                             (reduced value/contradiction)
                             (assoc slots k v))) content update))))

(def projection-template
  (template ::project [:slots]
            (fn [slots]
              (if (seq slots)
                (val (last (sort-by key slots)))
                value/nothing))))

(defn initial [id] (reducer/reducer-cell id merge-template projection-template))

(defn selection-update [id sequence identity]
  (reducer/reducer-slot-update id merge-template projection-template sequence identity))
