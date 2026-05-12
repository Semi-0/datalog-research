(ns differential-dataflow.graph 
  (:refer-clojure :exclude [map filter concat reduce count])
  (:require [clojure.core.async :as a]
            [clojure.core :as c]
            [differential-dataflow.multiset :as ms]
            [differential-dataflow.index :as index]))

;; Channel buffer sizes: unary ops are one-in / one-out per step → 1 is enough.
;; Zip + binary fan-in uses a small default; join / indexed reduce use a bit more headroom.

(def ^:private default-unary-buf 1)
(def ^:private default-binary-buf 8)
(def ^:private default-join-buf 16)
(def ^:private default-reduce-buf 16)

(def id (fn [x] x))

(defn unary-op
  ([f] (unary-op f default-unary-buf))
  ([f buf]
   (fn [ch-in]
     (let [ch-out (a/chan buf)]
       (a/go-loop []
         (if-some [pairs (a/<! ch-in)]
           (do (a/>! ch-out (f pairs))
               (recur))
           (a/close! ch-out)))
       ch-out))))

(defn map
  ([f] (map f default-unary-buf))
  ([f buf] (unary-op (ms/map f) buf)))

(defn filter
  ([f] (filter f default-unary-buf))
  ([f buf] (unary-op (ms/filter f) buf)))

(def negate (unary-op ms/negate))

(defn binary-op
  ([merge op] (binary-op merge op default-binary-buf))
  ([merge op buf]
   (fn [a b]
     (let [m (merge a b buf)
           out ((unary-op op buf) m)]
       out))))

(def concat
  (binary-op #(a/map vector [%1 %2] %3)
             (fn [ab] (ms/append (first ab) (second ab)))))

(defn delta-join
  [idx-a idx-b batch-a batch-b]
  (let [delta-a (index/from-multiset batch-a)
        delta-b (index/from-multiset batch-b)
        r1      (index/join-cartesian delta-a idx-b)
        idx-a1  (index/merge-deltas idx-a delta-a)
        r2      (index/join-cartesian idx-a1 delta-b)
        idx-b1  (index/merge-deltas idx-b delta-b)
        out     (ms/consolidate (into r1 r2))
        ks-a    (vec (keys idx-a1))
        ks-b    (vec (keys idx-b1))]
    [(index/compact-keys idx-a1 ks-a)
     (index/compact-keys idx-b1 ks-b)
     out]))

(defn join
  ([chan-a chan-b] (join chan-a chan-b default-join-buf))
  ([chan-a chan-b buf]
  (let [out (a/chan buf)
        zipped (a/map vector [chan-a chan-b] buf)]
    (a/go-loop [index-a index/empty-index
                index-b index/empty-index]
      (if-some [[batch-a batch-b] (a/<! zipped)]
        (let [[i-a i-b rows] (delta-join index-a index-b batch-a batch-b)]
          (when (a/>! out rows)
            (recur i-a i-b)))
        (a/close! out)))
    out)))

(defn reduce
  "Returns `(fn [ch-in] ch-out)` keyed multiset reducer; `f` maps per-key bag rows
  to multiset rows (see tests). Pipe like `((reduce f) in)` or `((reduce f buf) in)`."
  ([f] (reduce f default-reduce-buf))
  ([f buf]
   (fn [chan]
     (let [out (a/chan buf)]
       (a/go-loop [input-index index/empty-index
                   output-index index/empty-index]
         (if-some [current (a/<! chan)]
           (let [input-index' (index/merge-keyed-multiset-rows input-index current)
                 keys (vec (index/key-set current))
                 result (into []
                                (c/mapcat
                                  (fn [k]
                                    (let [curr (get input-index' k [])
                                          prev-out (get output-index k [])
                                          f-out (f curr)
                                          delta (ms/difference (ms/consolidate f-out)
                                                               (ms/consolidate prev-out))]
                                      (c/map (fn [[v m]] [[k v] m]) delta)))
                                  keys))
                 output-index' (index/merge-keyed-multiset-rows output-index result)
                 input-index'' (index/compact-keys input-index' keys)
                 output-index'' (index/compact-keys output-index' keys)]
             (when (a/>! out result)
               (recur input-index'' output-index'')))
           (a/close! out)))
       out))))

(defn count
  "Keyed multiset cardinality on each step: sums multiplicities per key in the bag
  `[[v mult] …]` (values ignored), emits delta rows vs last output — like Python `CountOperator`.
  Returns `(fn [ch-in] ch-out)`; pipe like `((count))` or `((count buf))`."
  ([] (reduce (fn [vals]
                (if (empty? vals)
                  []
                  [[(c/reduce (fn [acc [_ m]] (+ acc (long m))) 0 vals) 1]]))))
  ([buf] (reduce (fn [vals]
                   (if (empty? vals)
                     []
                     [[(c/reduce (fn [acc [_ m]] (+ acc (long m))) 0 vals) 1]]))
                 buf)))