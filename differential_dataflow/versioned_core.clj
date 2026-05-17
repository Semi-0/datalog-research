(ns differential-dataflow.versioned-core
  (:refer-clojure :exclude [data])
  (:require [differential-dataflow.frontier :as f]
            [differential-dataflow.stream-ops :as s]))

(defn data [version rows]
  (s/data-msg version rows))

(defn frontier [frontier]
  (s/frontier-msg frontier))

(defn emit [& messages]
  (vec (remove nil? messages)))

(defn as-frontier [x]
  (cond
    (set? x) (f/frontier x)
    (and (vector? x) (every? number? x)) (f/frontier [x])
    (sequential? x) (f/frontier x)
    :else (f/frontier [x])))

(defn emit-frontier [out-frontier candidate]
  (if (or (nil? out-frontier) (f/frontier-lt? out-frontier candidate))
    [candidate [(frontier candidate)]]
    [out-frontier []]))

(defn stateful [init {:keys [data frontier else]} buf]
  (s/scan-emit
   init
   (fn [state msg]
     (case (first msg)
       :data (let [[_ version rows] msg]
               (data state version rows))
       :frontier (let [[_ F] msg]
                   (frontier state (as-frontier F)))
       (if else
         (else state msg)
         [state []])))
   buf))

(defn unary [data-step frontier-step buf]
  (stateful
   {:out-frontier nil}
   {:data (fn [state version rows]
            [state (data-step version rows)])
    :frontier (fn [{:keys [out-frontier] :as state} F]
                (let [[out messages] (emit-frontier out-frontier (frontier-step F))]
                  [(assoc state :out-frontier out) messages]))
    :else (fn [state msg] [state [msg]])}
   buf))

(defn binary-frontier [state side F]
  (let [frontiers (assoc (:frontiers state) side F)
        candidate (when (every? some? (vals frontiers))
                    (f/frontier-meet (:a frontiers) (:b frontiers)))
        [out messages] (if candidate
                         (emit-frontier (:out-frontier state) candidate)
                         [(:out-frontier state) []])]
    [(assoc state :frontiers frontiers :out-frontier out) messages]))

(defn binary [a-ch b-ch init {:keys [data frontier else]} buf]
  (let [merged (s/merge-tagged [[:a a-ch] [:b b-ch]] buf)]
    ((s/scan-emit
      (merge {:frontiers {:a nil :b nil}
              :out-frontier nil}
             init)
      (fn [state [side msg]]
        (case (first msg)
          :data (let [[_ version rows] msg]
                  (data state side version rows))
          :frontier (let [[_ F] msg]
                      (if frontier
                        (frontier state side (as-frontier F))
                        (binary-frontier state side (as-frontier F))))
          (if else
            (else state side msg)
            [state []])))
      buf)
     merged)))
