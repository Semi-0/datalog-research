(ns graph.vijual.layout.stress-topology
  "Cache shortest-path rows by affected undirected component; pack pair constants."
  (:require [clojure.set :as set]
            [graph.vijual.layout.stress :as stress]))

(defn- affected-ids [previous adjacency]
  (let [old (:adjacency previous)
        ids (set/union (set (keys old)) (set (keys adjacency)))
        changed (filter #(not= (get old % ::absent) (get adjacency % ::absent)) ids)
        union-adj (merge-with set/union old adjacency)]
    ;; Reachability in the union includes both sides of a split or merge.
    (loop [pending (vec changed) seen (set changed)]
      (if (empty? pending)
        seen
        (let [id (peek pending)
              neighbors (set/difference (get union-adj id #{}) seen)]
          (recur (into (pop pending) neighbors) (into seen neighbors)))))))

(defn distances [ids edges previous]
  (let [adjacency (stress/undirected-adjacency ids edges)
        affected (affected-ids previous adjacency)
        rows (into {} (map (fn [id]
                            [id (if (contains? affected id)
                                  (stress/graph-distances-from adjacency id)
                                  (get-in previous [:rows id]))]) ids))
        diameter (reduce (fn [maximum row] (reduce max maximum (vals row))) 1 (vals rows))]
    {:adjacency adjacency :rows rows :diameter diameter
     :sources-recomputed (count (filter #(contains? affected %) ids))}))

(defn pack [ids {:keys [rows diameter]} opts]
  (let [n (count ids)
        length (quot (* n (dec n)) 2)
        aa (long-array length) bb (long-array length)
        ds (double-array length) ws (double-array length)
        totals (double-array n)
        edge-length (double (:edge-length opts))
        disconnected (double (:disconnected-weight opts))]
    (dotimes [a n]
      (doseq [b (range (inc a) n)]
        (let [p (+ (quot (* a (- (* 2 n) a 1)) 2) (- b a 1))
              distance (get-in rows [(ids a) (ids b)])
              d (if distance (double distance) (double (+ diameter 2)))
              weight (if distance (/ 1.0 (* d d)) disconnected)]
          (aset-long aa p a)
          (aset-long bb p b)
          (aset-double ds p (* edge-length d))
          (aset-double ws p weight)
          (aset-double totals a (+ (aget totals a) weight))
          (aset-double totals b (+ (aget totals b) weight)))))
    {:aa aa :bb bb :ds ds :ws ws :totals totals :n n :pair-count length
     :gap (double (:gap opts))}))

(defn prepare [ids edges opts previous]
  (let [paths (distances ids edges (:paths previous))]
    {:paths paths :packed (pack ids paths opts)}))
