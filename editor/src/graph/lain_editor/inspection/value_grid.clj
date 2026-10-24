(ns graph.lain-editor.inspection.value-grid
  "Complete public values as rows, including TTMS source identities."
  (:require [propagators.semantic-trace.capture :as capture]
            [graph.lain-editor.inspection.calls :as calls]
            [graph.lain-editor.inspection.value-labels :as labels]
            [graph.lain-editor.inspection.structural-value :as structure]
            [propagators.cells.value :as value]
            [propagators.ids :as ids]
            [propagators.experimental.network-history :as history]
            [propagators.network :as net]))

(defn cell-token [id]
  (if (ids/node-id? id) (str "cell/" (:uuid id)) nil))

(defn- key-text [key]
  (if (keyword? key) (subs (str key) 1) (labels/datum-text key)))

(declare rows*)

(defn- read-rows [title result depth reader]
  (if (:found? result)
    (rows* title (:value result) depth reader)
    [{:key title :value (:reason result) :depth depth}]))

(defn- object-rows [title v depth reader]
  (let [keys (sort-by pr-str (labels/public-keys v))]
    (if (seq keys)
      (into (if (seq title) [{:key title :value "" :depth depth}] [])
            (mapcat #(read-rows (key-text %) (structure/slot v % reader)
                                (if (seq title) (inc depth) depth) reader)) keys)
      [{:key title :value "{}" :depth depth}])))

(defn- collection-rows [title v depth reader]
  (if (seq v)
    (into (if (seq title) [{:key title :value "" :depth depth}] [])
          (mapcat (fn [[index item]] (rows* (str index) item (if (seq title) (inc depth) depth) reader)))
          (map-indexed vector v))
    [{:key title :value (labels/datum-text v) :depth depth}]))

(defn- list-rows [title v depth reader]
  (let [{:keys [items tail]} (structure/linked-value v reader)
        tail (if (contains? tail :value)
               (assoc tail :rows (rows* "" (:value tail) 0 reader))
               tail)]
    [{:key title :value "" :depth depth
      :linkedList {:items (mapv #(read-rows "" % 0 reader) items) :tail tail}}]))

(defn- rows* [title v depth reader]
  (cond
    (ids/node-id? v)
    [(cond-> {:key title :value (labels/datum-text v) :depth depth}
       (= title "source") (assoc :sourceToken (pr-str v) :sourceReference (cell-token v)))]
    (or (value/nothing? v) (value/contradiction? v))
    [{:key title :value (labels/datum-text v) :depth depth}]
    (or (capture/semantic-graph? v) (history/replay? v) (history/recording? v))
    [{:key title :value (labels/datum-text v) :depth depth}]
    (structure/linked? v) (list-rows title v depth reader)
    (calls/callable-inputs v) [{:key title :value (labels/datum-text v) :depth depth}]
    (or (net/net? v) (map? v)) (object-rows title v depth reader)
    (set? v) (collection-rows title (if (seq v) (sort-by pr-str v) v) depth reader)
    (sequential? v) (collection-rows title v depth reader)
    :else [{:key title :value (labels/datum-text v) :depth depth}]))

(defn rows
  ([v] (rows v structure/unavailable))
  ([v reader] (rows* "" v 0 reader)))
