(ns differential-dataflow.differential-dataflow-v1
  "Keyed difference trace: one multiset per timestep (Python `DifferenceSequence`).
  Each multiset row is `[[key value] multiplicity]`; nesting rules → `differential-dataflow.multiset`."
  (:require [differential-dataflow.multiset
             :refer [multiset-append multiset-consolidate multiset-filter multiset-map
                     multiset-negate multiset-subtract-ms]]))

;;; Index: map key → bag of [value multiplicity] vectors

(def ^:private index-empty {})

(defn- index-add-at
  "Add one `[value mult]` under `key`."
  [index key value mult]
  (assoc index key (multiset-append (get index key []) [[value mult]])))

(defn- index-merge-deltas
  "Merge `delta` into `index`: same keys get `multiset-append` on their bags."
  [index delta]
  (merge-with multiset-append index delta))

(defn- index-compact-keys
  "Consolidate each listed key's bag (merge duplicate values, drop zeros)."
  [index keys]
  (reduce (fn [idx k] (assoc idx k (multiset-consolidate (get idx k [])))) index keys))

(defn- index-join-cartesian
  "For keys in both maps, emit `[[key [v1 v2]] (* m1 m2)]` for every pair of entries."
  [left right]
  (vec (sort (for [k (sort (keys left))
                  :when (contains? right k)
                  [v1 m1] (get left k [])
                  [v2 m2] (get right k [])]
              [[k [v1 v2]] (* (long m1) (long m2))]))))

(defn- multiset->index-by-key
  "Turn multiset rows `[[[k v] m] ...]` into a key-indexed map of bags."
  [multiset-rows]
  (reduce (fn [idx [[k v] m]] (index-add-at idx k v m)) index-empty multiset-rows))

(defn- index-merge-collection
  "Fold multiset rows into `index`; return `[next-index keys-seen]`."
  [index multiset-rows]
  (reduce (fn [[idx keys] [[k v] m]]
            [(index-add-at idx k v m) (conj keys k)])
          [index #{}]
          multiset-rows))

(defn- trace-reduce-step-keys
  "For each key in `sorted-keys`, emit multiset delta of `[[k v] m]` rows vs prior out-index."
  [f index-in index-out sorted-keys]
  (reduce (fn [[pairs out-idx] key]
            (let [curr-in (get index-in key [])
                  curr-out (get out-idx key [])
                  next-out (f curr-in)
                  delta (multiset-subtract-ms next-out curr-out)
                  pairs* (into pairs (map (fn [[v m]] [[key v] m]) delta))
                  out-idx* (reduce (fn [idx [v m]] (index-add-at idx key v m))
                                   out-idx
                                   delta)]
              [pairs* out-idx*]))
          [[] index-out]
          sorted-keys))

;;; Trace: vector of multisets

(defn trace-map [f trace]
  (mapv (partial multiset-map f) trace))

(defn trace-filter [pred trace]
  (mapv (partial multiset-filter pred) trace))

(defn trace-negate [trace]
  (mapv multiset-negate trace))

(defn- pad-trace [trace len]
  (vec (take len (concat trace (repeat [])))))

(defn trace-append [trace-a trace-b]
  (let [len (max (count trace-a) (count trace-b))]
    (mapv multiset-append (pad-trace trace-a len) (pad-trace trace-b len))))

(defn trace-consolidate [trace]
  (mapv multiset-consolidate trace))

(defn trace-join [trace-a trace-b]
  (let [len (max (count trace-a) (count trace-b))
        padded-a (pad-trace trace-a len)
        padded-b (pad-trace trace-b len)]
    (vec (second
          (reduce (fn [[[idx-a idx-b] rows] step]
                    (let [delta-a (multiset->index-by-key (nth padded-a step))
                          delta-b (multiset->index-by-key (nth padded-b step))
                          part-a (index-join-cartesian delta-a idx-b)
                          idx-a* (index-merge-deltas idx-a delta-a)
                          part-b (index-join-cartesian idx-a* delta-b)
                          idx-b* (index-merge-deltas idx-b delta-b)
                          row (multiset-consolidate (multiset-append part-a part-b))]
                      [[idx-a* idx-b*] (conj rows row)]))
                  [[index-empty index-empty] []]
                  (range len))))))

(defn trace-reduce [f trace]
  (vec
   (peek (reduce (fn [[index-in index-out rows] coll]
                   (let [[index* keys-here] (index-merge-collection index-in coll)
                         keys-sorted (vec (sort keys-here))
                         [pairs out*] (trace-reduce-step-keys f index* index-out keys-sorted)]
                     [(index-compact-keys index* keys-sorted)
                      (index-compact-keys out* keys-sorted)
                      (conj rows pairs)]))
                 [index-empty index-empty []]
                 trace))))

(defn trace-count [trace]
  (trace-reduce
   (fn [value-bag]
     (let [n (reduce (fn [acc [_v m]] (+ acc (long m))) 0 value-bag)]
       [[n 1]]))
   trace))

(defn trace-sum [trace]
  (trace-reduce
   (fn [value-bag]
     (let [n (reduce (fn [acc [v m]] (+ acc (* (long v) (long m)))) 0 value-bag)]
       [[n 1]]))
   trace))

(defn- reduce-extreme-by-key
  "`pick` combines accumulator and next value (e.g. min / max). `label` is for errors."
  [label pick trace]
  (trace-reduce
   (fn [value-bag]
     (let [xs (multiset-consolidate value-bag)]
       (if (empty? xs)
         []
         (let [[[v0 m0] & rest-rows] xs]
           (when-not (pos? m0)
             (throw (ex-info (str label " needs positive multiplicity") {:v v0 :m m0})))
           [[(reduce (fn [acc [v m]]
                        (if (pos? m)
                          (pick acc v)
                          (throw (ex-info (str label " needs positive multiplicity")
                                          {:v v :m m}))))
                      v0
                      rest-rows)
             1]]))))
   trace))

(defn trace-min [trace]
  (reduce-extreme-by-key "min" (fn [acc v] (if (< v acc) v acc)) trace))

(defn trace-max [trace]
  (reduce-extreme-by-key "max" (fn [acc v] (if (> v acc) v acc)) trace))

(defn trace-distinct [trace]
  (trace-reduce
   (fn [value-bag]
     (let [xs (multiset-consolidate value-bag)]
       (doseq [[_ m] xs :when (<= m 0)]
         (throw (ex-info "distinct needs positive multiplicity" {:m m})))
       (mapv (fn [[v _]] [v 1]) xs)))
   trace))

(defn trace-iterate [_ _]
  (throw (ex-info "trace-iterate not implemented" {})))
