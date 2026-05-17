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

(defn data-msg
  [version batch]
  [:data version batch])

(defn frontier-msg
  [frontier]
  [:frontier frontier])

(defn data-msg?
  [msg]
  (= :data (first msg)))

(defn frontier-msg?
  [msg]
  (= :frontier (first msg)))

(defn on-data
  "Transform only `[:data t x]` messages and forward frontier messages."
  ([f] (on-data f default-buf))
  ([f buf]
   (pipe (fn [[tag x y :as msg]]
           (case tag
             :data [:data x (f y)]
             :frontier msg
             msg))
         buf)))

(defn scan-emit
  "Stateful stream combinator.

  `step` receives state and input and returns `[new-state messages]`, where
  messages is a seq of zero or more output messages."
  ([init step] (scan-emit init step default-buf))
  ([init step buf]
   (fn [in]
     (let [out (a/chan buf)]
       (a/go-loop [state init]
         (if-some [x (a/<! in)]
           (let [[state' messages] (step state x)]
             (doseq [message messages]
               (a/>! out message))
             (recur state'))
           (a/close! out)))
       out))))

(defn- tag-channel
  [tag ch buf]
  (let [out (a/chan buf)]
    (a/go-loop []
      (if-some [msg (a/<! ch)]
        (do (a/>! out [tag msg])
            (recur))
        (a/close! out)))
    out))

(defn merge-tagged
  "Merge channels into one stream of `[tag msg]`.

  `tagged-channels` may be a map or a seq of `[tag channel]` pairs."
  ([tagged-channels] (merge-tagged tagged-channels default-buf))
  ([tagged-channels buf]
   (let [pairs (if (map? tagged-channels)
                 tagged-channels
                 (into [] tagged-channels))]
     (a/merge (mapv (fn [[tag ch]] (tag-channel tag ch buf)) pairs) buf))))

(defn pipe-to!
  "Copy every value from `source` to `dest`; close `dest` when `source` closes."
  [source dest]
  (a/go-loop []
    (if-some [msg (a/<! source)]
      (do (a/>! dest msg)
          (recur))
      (a/close! dest)))
  dest)

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
