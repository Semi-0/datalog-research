(ns propagators.dataflow-projection
  "Pure graph normalization; no network evaluation or renderer policy."
  (:require [clojure.set :as set]))

(defn return-aliases
  "An expression returning one declared output names that output, not new data.
  Multi-output applications retain their separate result identity."
  [applications]
  (into {}
        (keep (fn [{:keys [result outputs]}]
                (if (and (= 1 (count outputs)) (not= result (first outputs)))
                  [result (first outputs)]
                  nil)))
        applications))

(defn canonical [aliases node]
  (loop [current node seen #{}]
    (if-let [next-node (get aliases current)]
      (if (contains? seen current)
        (throw (ex-info "Cyclic dataflow return aliases" {:node node :aliases aliases}))
        (recur next-node (conj seen current)))
      current)))

(defn rename-application [aliases app]
  (reduce (fn [a field]
            (update a field #(mapv (partial canonical aliases) %)))
          (update app :result (partial canonical aliases))
          [:inputs :outputs :arguments]))

(defn rename-edges [aliases edges]
  (vec (distinct
        (keep (fn [[from to]]
                (let [a (canonical aliases from) b (canonical aliases to)]
                  (if (and (= a b) (not= from to)) nil [a b]))) edges))))

(defn connected-nodes
  "Union of undirected semantic components containing the supplied seeds."
  [edges seeds]
  (let [adjacency (reduce (fn [g [a b]]
                            (-> g (update a (fnil conj #{}) b)
                                (update b (fnil conj #{}) a))) {} edges)]
    (loop [pending (vec seeds) seen (set seeds)]
      (if (empty? pending)
        seen
        (let [current (peek pending)
              neighbors (set/difference (get adjacency current #{}) seen)]
          (recur (into (pop pending) neighbors) (into seen neighbors)))))))

(defn select-edges [edges seeds]
  (if (seq seeds)
    (let [selected (connected-nodes edges seeds)]
      (filterv (fn [[a b]] (and (contains? selected a) (contains? selected b))) edges))
    edges))
