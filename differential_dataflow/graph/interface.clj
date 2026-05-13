(ns differential-dataflow.graph.interface
  (:refer-clojure :exclude [map filter concat reduce count])
  (:require [differential-dataflow.graph :as gr]))

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
   (gr/map-op f buf-sz)))

(defn filter
  ([pred] (filter pred (buf :unary)))
  ([pred buf-sz]
   (gr/filter-op pred buf-sz)))

(def negate
  "Channel op `(fn [ch-in] ch-out)` with unary buffer size."
  (gr/negate-op (buf :unary)))

(defn concat
  ([a b] (concat a b (buf :default)))
  ([a b buf-sz]
   (gr/concat-op a b buf-sz)))

(defn join
  ([a b] (join a b (buf :join)))
  ([a b buf-sz]
   (gr/join-op a b buf-sz)))

(defn reduce
  "Returns `(fn [ch-in] ch-out)` keyed multiset reducer; `f` maps per-key bag rows
  to multiset rows (see tests). Pipe like `((reduce f) in)` or `((reduce f buf) in)`."
  ([f] (reduce f (buf :reduce)))
  ([f buf-sz]
   (gr/reduce-op f buf-sz)))

(defn count
  "Keyed multiset cardinality on each step: sums multiplicities per key in the bag
  `[[v mult] …]` (values ignored), emits delta rows vs last output — like Python `CountOperator`.
  Returns `(fn [ch-in] ch-out)`; pipe like `((count))` or `((count buf))`."
  ([] (gr/count-op (buf :reduce)))
  ([buf-sz]
   (gr/count-op buf-sz)))
