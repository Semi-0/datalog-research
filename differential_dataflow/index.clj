(ns differential-dataflow.index
  "Index: map from key → multiset of `[value multiplicity]` rows (per-key bags).
  Used by trace join/reduce to group keyed multiset rows."
  (:require [differential-dataflow.multiset
             :refer [multiset-append multiset-consolidate]]))

(def empty-index
  "Empty index (no keys)."
  {})

(defn add-at
  "Add one `[value mult]` under `key`."
  [index key value mult]
  (assoc index key (multiset-append (get index key []) [[value mult]])))

(defn merge-deltas
  "Merge `delta` into `index`: same keys get `multiset-append` on their bags."
  [index delta]
  (merge-with multiset-append index delta))

(defn compact-keys
  "Consolidate each listed key's bag (merge duplicate values, drop zeros)."
  [index keys]
  (reduce (fn [idx k] (assoc idx k (multiset-consolidate (get idx k [])))) index keys))

(defn join-cartesian
  "For keys in both maps, emit `[[key [v1 v2]] (* m1 m2)]` for every pair of entries."
  [left right]
  (vec (sort (for [k (sort (keys left))
                  :when (contains? right k)
                  [v1 m1] (get left k [])
                  [v2 m2] (get right k [])]
              [[k [v1 v2]] (* (long m1) (long m2))]))))

(defn merge-keyed-multiset-rows
  "Fold multiset rows shaped `[[k v] m]` into `index` (append each row under its key)."
  [index multiset-rows]
  (reduce (fn [idx [[k v] m]] (add-at idx k v m)) index multiset-rows))

(defn from-multiset
  "Turn multiset rows `[[[k v] m] ...]` into a key-indexed map of bags."
  [multiset-rows]
  (merge-keyed-multiset-rows empty-index multiset-rows))

(defn key-set
  "Set of keys `k` from multiset rows shaped `[[k v] m]`."
  [multiset-rows]
  (into #{} (map (fn [[[k _v] _m]] k)) multiset-rows))

(defn merge-value-rows-at
  "Append each `[value mult]` under the single logical `key`."
  [index key value-mult-rows]
  (reduce (fn [idx [v m]] (add-at idx key v m)) index value-mult-rows))

(defn merge-collection
  "Fold multiset rows into `index`; return `[next-index keys-seen]`."
  [index multiset-rows]
  [(merge-keyed-multiset-rows index multiset-rows)
   (key-set multiset-rows)])
