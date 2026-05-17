(ns differential-dataflow.versioned-core
  (:refer-clojure :exclude [data])
  (:require [differential-dataflow.frontier :as f]
            [differential-dataflow.stream-ops :as s]))

(defn data [version rows]
  (s/data-msg version rows))

(defn frontier [frontier]
  (s/frontier-msg frontier))

(defn data? [msg]
  (= :data (first msg)))

(defn frontier? [msg]
  (= :frontier (first msg)))

(defn version [[_ version _rows]]
  version)

(defn rows [[_ _version rows]]
  rows)

(defn frontier-value [[_ frontier]]
  frontier)

(defn on-data [msg f default]
  (if (data? msg) (f (version msg) (rows msg)) default))

(defn on-frontier [msg f default]
  (if (frontier? msg) (f (frontier-value msg)) default))

(defn normalize-frontier [x]
  (cond
    (set? x) (f/frontier x)
    (and (vector? x) (every? number? x)) (f/frontier [x])
    (sequential? x) (f/frontier x)
    :else (f/frontier [x])))

(defn advance-frontier
  "Advance `:out-frontier` to `candidate` when it strictly increases.
  Returns `[state messages]`."
  [state candidate]
  (let [candidate (normalize-frontier candidate)
        out-frontier (:out-frontier state)]
    (if (or (nil? out-frontier) (f/frontier-lt? out-frontier candidate))
      [(assoc state :out-frontier candidate) [(frontier candidate)]]
      [state []])))

(defn frontier-step [state candidate]
  (let [[state' messages] (advance-frontier state candidate)]
    {:state state' :messages messages}))

(defn advance-binary-frontier
  "Record one input frontier. Once both inputs are known, emit the meet:
  `output-frontier = meet(input-a-frontier, input-b-frontier)`."
  [state side frontier]
  (let [frontiers (assoc (:frontiers state) side (normalize-frontier frontier))
        state' (assoc state :frontiers frontiers)]
    (if (every? some? (vals frontiers))
      (advance-frontier state' (f/frontier-meet (:a frontiers) (:b frontiers)))
      [state' []])))

(defn binary-frontier-step [state side frontier]
  (let [[state' messages] (advance-binary-frontier state side frontier)]
    {:state state' :messages messages}))

(defn unary-operator
  ([init step emit] (unary-operator init step emit 8))
  ([init step emit buf]
   (s/scan-emit
    init
    (fn [state msg]
      (let [state' (step state msg)]
        [state' (emit state state' msg)]))
    buf)))

(defn binary-operator
  ([a-ch b-ch init step emit] (binary-operator a-ch b-ch init step emit 8))
  ([a-ch b-ch init step emit buf]
   (let [merged (s/merge-tagged [[:a a-ch] [:b b-ch]] buf)]
     ((s/scan-emit
       (merge {:frontiers {:a nil :b nil}
               :out-frontier nil}
              init)
       (fn [state [side msg]]
         (let [state' (step state side msg)]
           [state' (emit state state' side msg)]))
       buf)
      merged))))
