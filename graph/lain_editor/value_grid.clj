(ns graph.lain-editor.value-grid
  "Complete public values as rows. Event timestamps retain separate source identities."
  (:require [graph.lain-editor.call-inspection :as calls]
            [graph.lain-editor.value-labels :as labels]
            [graph.lain-editor.structural-value :as structure]
            [propagators.cells.value :as value]
            [propagators.datastructures.event :as event]
            [propagators.ids :as ids]
            [propagators.network :as net]))

(defn cell-token [id]
  (if (ids/node-id? id) (str "cell/" (:uuid id)) nil))

(defn- key-text [key]
  (if (keyword? key) (subs (str key) 1) (labels/datum-text key)))

(declare rows*)

(defn- timestamp-rows [fact depth reader]
  (mapcat (fn [{:keys [source input-id timestamp]}]
            (map #(assoc % :sourceToken (pr-str source) :sourceReference (cell-token input-id))
                 (rows* "timestamp" timestamp depth reader)))
          (sort-by (juxt (comp pr-str :source) (comp pr-str :input-id))
                   (event/fact-evidence fact))))

(defn- event-rows [v depth reader]
  (vec (mapcat (fn [fact]
                 (concat (rows* "base" (event/event-value fact) depth reader)
                         (rows* "source-state" (event/source-state fact) depth reader)
                         (timestamp-rows fact depth reader)))
               (sort-by (comp pr-str event/timestamp) (event/facts v)))))

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
    (or (value/nothing? v) (value/contradiction? v) (ids/node-id? v))
    [{:key title :value (labels/datum-text v) :depth depth}]
    (structure/linked? v) (list-rows title v depth reader)
    (event/event-protocol-value? v)
    (if (seq title)
      (into [{:key title :value "" :depth depth}] (event-rows v (inc depth) reader))
      (event-rows v depth reader))
    (calls/callable-inputs v) [{:key title :value (labels/datum-text v) :depth depth}]
    (or (net/net? v) (map? v)) (object-rows title v depth reader)
    (set? v) (collection-rows title (sort-by pr-str v) depth reader)
    (sequential? v) (collection-rows title v depth reader)
    :else [{:key title :value (labels/datum-text v) :depth depth}]))

(defn rows
  ([v] (rows v structure/unavailable))
  ([v reader] (rows* "" v 0 reader)))
