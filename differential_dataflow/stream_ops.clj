(ns differential-dataflow.stream-ops
  (:require [clojure.core.async :as a]))

(def ^:private default-buf 8)

(defn pipe
  "Transform each message from `in` with `f`."
  ([f] (pipe f default-buf))
  ([f buf]
   (fn [in]
     (let [out (a/chan buf)]
       (a/go-loop []
         (if-some [x (a/<! in)]
           (when (a/>! out (f x))
             (recur))
           (a/close! out)))
       out))))

(defn scan
  "Stateful stream transducer.
   `step` receives state and input, returns [new-state output]."
  ([init step] (scan init step default-buf))
  ([init step buf]
   (fn [in]
     (let [out (a/chan buf)]
       (a/go-loop [state init]
         (if-some [x (a/<! in)]
           (let [[state' y] (step state x)]
             (when (a/>! out y)
               (recur state')))
           (a/close! out)))
       out))))

(defn zip
  ([a b] (zip a b default-buf))
  ([a b buf]
   (a/map vector [a b] buf)))
