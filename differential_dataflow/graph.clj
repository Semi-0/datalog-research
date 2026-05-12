(ns differential-dataflow.graph
  (:refer-clojure :exclude [map filter concat reduce count])
  (:require [clojure.core :as c]
            [differential-dataflow.multiset :as ms]
            [differential-dataflow.index :as index]
            [differential-dataflow.stream-ops :as s]))

;;; ---------------------------------------------------------------------------
;;; Core (buffer-explicit building blocks)

(defn- map-op [f buf]
  (s/pipe (ms/map f) buf))

(defn- filter-op [pred buf]
  (s/pipe (ms/filter pred) buf))

(defn- negate-op [buf]
  (s/pipe ms/negate buf))

(defn- concat-op [a b buf]
  ((s/pipe (fn [[x y]]
             (ms/append x y))
           buf)
   (s/zip a b buf)))

(defn- delta-join
  [[idx-a idx-b] [batch-a batch-b]]
  (let [delta-a (index/from-multiset batch-a)
        delta-b (index/from-multiset batch-b)

        rows-a  (index/join-cartesian delta-a idx-b)
        idx-a'  (index/merge-deltas idx-a delta-a)

        rows-b  (index/join-cartesian idx-a' delta-b)
        idx-b'  (index/merge-deltas idx-b delta-b)

        rows    (ms/consolidate (into rows-a rows-b))
        keys-a  (vec (keys idx-a'))
        keys-b  (vec (keys idx-b'))]
    [[(index/compact-keys idx-a' keys-a)
      (index/compact-keys idx-b' keys-b)]
     rows]))

(defn- join-op [a b buf]
  ((s/scan [index/empty-index index/empty-index]
           delta-join
           buf)
   (s/zip a b buf)))

(defn- reduce-step
  [f [input-index output-index] current]
  (let [input-index' (index/merge-keyed-multiset-rows input-index current)
        key-vec      (vec (index/key-set current))

        result
        (into []
              (c/mapcat
               (fn [k]
                 (let [curr     (get input-index' k [])
                       prev-out (get output-index k [])
                       next-out (ms/consolidate (f curr))
                       delta    (ms/difference next-out
                                               (ms/consolidate prev-out))]
                   (c/map (fn [[v m]] [[k v] m]) delta)))
               key-vec))

        output-index' (index/merge-keyed-multiset-rows output-index result)]

    [[(index/compact-keys input-index' key-vec)
      (index/compact-keys output-index' key-vec)]
     result]))

(defn- reduce-op [f buf]
  (s/scan [index/empty-index index/empty-index]
          (partial reduce-step f)
          buf))

(defn- sum-multiplicities [rows]
  (c/reduce (fn [acc [_ m]]
              (+ acc (long m)))
            0
            rows))

(defn- count-fn [rows]
  (if (seq rows)
    [[(sum-multiplicities rows) 1]]
    []))

(defn- count-op [buf]
  (reduce-op count-fn buf))

;;; ---------------------------------------------------------------------------
;;; Public: default buffer sizes + curried operators

(def default-bufs
  {:default 8
   :unary   1
   :join    16
   :reduce  16})

(defn buf
  "Buffer size for keyword `k` (`:default`, `:unary`, `:join`, `:reduce`).
   Unknown keys fall back to `:default`."
  [k]
  (get default-bufs k (:default default-bufs)))

(defn map
  ([f] (map f (buf :unary)))
  ([f buf-sz]
   (map-op f buf-sz)))

(defn filter
  ([pred] (filter pred (buf :unary)))
  ([pred buf-sz]
   (filter-op pred buf-sz)))

(def negate
  "Channel op `(fn [ch-in] ch-out)` with unary buffer size."
  (negate-op (buf :unary)))

(defn concat
  ([a b] (concat a b (buf :default)))
  ([a b buf-sz]
   (concat-op a b buf-sz)))

(defn join
  ([a b] (join a b (buf :join)))
  ([a b buf-sz]
   (join-op a b buf-sz)))

(defn reduce
  "Returns `(fn [ch-in] ch-out)` keyed multiset reducer; `f` maps per-key bag rows
  to multiset rows (see tests). Pipe like `((reduce f) in)` or `((reduce f buf) in)`."
  ([f] (reduce f (buf :reduce)))
  ([f buf-sz]
   (reduce-op f buf-sz)))

(defn count
  "Keyed multiset cardinality on each step: sums multiplicities per key in the bag
  `[[v mult] …]` (values ignored), emits delta rows vs last output — like Python `CountOperator`.
  Returns `(fn [ch-in] ch-out)`; pipe like `((count))` or `((count buf))`."
  ([] (count-op (buf :reduce)))
  ([buf-sz]
   (count-op buf-sz)))
