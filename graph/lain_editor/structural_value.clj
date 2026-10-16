(ns graph.lain-editor.structural-value
  "Read-only public slots and linked lists; no propagators or live fallback in replay."
  (:require [graph.lain-editor.value-labels :as labels]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as object]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]))

(def empty-marker :compiler-2/list-empty)
(defn unavailable [_] {:found? false :reason "unavailable"})

(defn network-reader [network]
  (fn [id]
    (if (and network (contains? (net/net-env network) id))
      {:found? true :value (net/network-cell-strongest network id)}
      (unavailable id))))

(defn snapshot-reader [graph path]
  (fn [id]
    (let [key [path id] values (:values graph)
          targets (if (contains? values key) [key] (get (:node-aliases graph) key))
          available (filter #(contains? values %) targets)
          candidates (distinct (map values available))]
      (if (= 1 (count candidates))
        {:found? true :value (first candidates)}
        {:found? false :reason (if (seq candidates) "ambiguous" "unavailable")}))))

(defn slot [v key reader]
  (cond
    (object/accessor-source-slot-present? v key)
    {:found? true :value (object/accessor-source-slot-value v key)}
    (object/accessor-network? v)
    (let [parents (object/accessor-parent-ids v key)
          results (mapv reader parents)
          candidates (distinct (map :value (filter :found? results)))]
      (if (and (seq results) (every? :found? results) (= 1 (count candidates)))
        {:found? true :value (first candidates)}
        {:found? false :reason (if (> (count candidates) 1) "ambiguous" "unavailable")}))
    :else {:found? true :value (labels/public-slot v key)}))

(defn linked? [v]
  (or (= empty-marker v) (list? v)
      (and (net/net? v) (every? (set (labels/public-keys v)) [:car :cdr]))))

(defn linked-value [v reader]
  (loop [current (data/payload v) items [] seen []]
    (cond
      (= empty-marker current) {:items items :tail {:kind "empty"}}
      (list? current) {:items (into items (map #(hash-map :found? true :value %) current)) :tail {:kind "empty"}}
      (value/nothing? current) {:items items :tail {:kind "pending"}}
      (value/contradiction? current) {:items items :tail {:kind "contradiction" :value current}}
      (some #(identical? current %) seen) {:items items :tail {:kind "cycle"}}
      (linked? current)
      (let [head (slot current :car reader) tail (slot current :cdr reader)
            items (conj items head)]
        (if (:found? tail)
          (recur (data/payload (:value tail)) items (conj seen current))
          {:items items :tail {:kind (:reason tail)}}))
      :else {:items items :tail {:kind "improper" :value current}})))
