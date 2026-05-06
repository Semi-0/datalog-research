(ns differential-dataflow.differential-dataflow-v1
  "Keyed difference trace: one multiset per timestep (Python `DifferenceSequence`).
  Each multiset row is `[[key value] multiplicity]`; nesting rules → `differential-dataflow.multiset`.
  Key → bag-of-values lives in `differential-dataflow.index`."
  (:require [differential-dataflow.index :as index]
            [differential-dataflow.multiset
             :refer [multiset-append multiset-consolidate multiset-filter multiset-map
                     multiset-negate multiset-difference]]))

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
                    (let [delta-a (index/from-multiset (nth padded-a step))
                          delta-b (index/from-multiset (nth padded-b step))
                          part-a (index/join-cartesian delta-a idx-b)
                          idx-a* (index/merge-deltas idx-a delta-a)
                          part-b (index/join-cartesian idx-a* delta-b)
                          idx-b* (index/merge-deltas idx-b delta-b)
                          row (multiset-consolidate (multiset-append part-a part-b))]
                      [[idx-a* idx-b*] (conj rows row)]))
                  [[index/empty-index index/empty-index] []]
                  (range len))))))

(defn merge-collection [f indexed-input indexed-output collection]
  (let [*indexed-input (reduce (fn [state, next-element]
                        (let [[[key, value], multiciplicty] next-element]
                          (index/add-at state key, value, multiciplicty))) 
                      indexed-input
                      collection)
        keys (sort (into #{} (map (fn [[[k _v] _m]] k)) collection))]
    (loop [accumulated []
           *indexed-output indexed-output
           keys-seq (seq keys)]
      (if (empty? keys-seq)
        (let [ks (vec keys)
              compacted-input (index/compact-keys *indexed-input ks)
              compacted-output (index/compact-keys *indexed-output ks)]
          [compacted-input compacted-output accumulated])
        (let [curr-key        (first keys-seq)
              curr-item       (get *indexed-input curr-key [])
              prev            (get *indexed-output curr-key [])
              curr            (f curr-item)
              delta           (multiset-difference curr prev)
              new-accumulated (reduce (fn [acc [value multiplicity]] 
                                        (conj acc [[curr-key value] multiplicity])) 
                                      accumulated
                                      delta)
              new-index       (reduce (fn [old-index [value multiplicity]] 
                                        (index/add-at old-index curr-key value multiplicity))
                                      *indexed-output
                                      delta)]
          (recur new-accumulated
                 new-index
                 (rest keys-seq)))))))


(defn trace-patch-step
  "Binary step for folding over timesteps: `(partial trace-patch-step f)` matches ordinary
  `reduce` shape `[state patch] → next-state`. `state` is
  `[indexed-input indexed-output chunks]`; `f` is still the unary per-key aggregator passed to
  `merge-collection`."
  [f [indexed-input indexed-output chunks] patch]
  (let [[i-in i-out r] (merge-collection f indexed-input indexed-output patch)]
    [i-in i-out (conj chunks r)]))

(defn trace-reduce
  "Fold `trace` with `(partial trace-patch-step f)`; see also `trace-patch-step`."
  [f trace]
  (vec
   (peek (reduce (partial trace-patch-step f)
                  [index/empty-index index/empty-index []]
                  trace))))

(def ^{:doc "Alias of `trace-reduce` (historical name)."}
  *trace-reduce trace-reduce)

(defn trace-reduce-rows
  "Per-key aggregate like ordinary `reduce`: fold multiset rows `[datum mult]` with `(rf acc row)`,
  starting at `init`, then publish a single row `[[result 1]]` for that key."
  [init rf trace]
  (trace-reduce (fn [vals] [[(reduce rf init vals) 1]]) trace))

(defn trace-count [trace]
  (trace-reduce-rows 0 (fn [acc [_ m]] (+ acc m)) trace))

(defn trace-sum [trace]
  (trace-reduce-rows 0 (fn [acc [v m]] (+ acc (* v m))) trace))

(defn- trace-via-consolidated-rows
  "`emit` receives `(multiset-consolidate vals)` for each key’s bag `vals`."
  [emit trace]
  (trace-reduce (fn [vals] (emit (multiset-consolidate vals))) trace))

(defn trace-reduce-rows-consolidated
  "Like `trace-reduce-rows`, but folds **after** `multiset-consolidate` on the value bag.
  Use `(fn [acc [datum mult]] …)`; `init` may be `nil` so the first row can seed (see `trace-min`)."
  [init rf trace]
  (trace-via-consolidated-rows
    (fn [xs]
      (if (empty? xs) [] [[(reduce rf init xs) 1]]))
    trace))

(defn trace-min [trace]
  (trace-reduce-rows-consolidated
    nil
    (fn [acc [v m]]
      (when-not (pos? m)
        (throw (ex-info "min needs positive multiplicity" {:v v :m m})))
      (if (nil? acc) v (if (< v acc) v acc)))
    trace))

(defn trace-max [trace]
  (trace-reduce-rows-consolidated
    nil
    (fn [acc [v m]]
      (when-not (pos? m)
        (throw (ex-info "max needs positive multiplicity" {:v v :m m})))
      (if (nil? acc) v (if (> v acc) v acc)))
    trace))

(defn trace-distinct [trace]
  (trace-via-consolidated-rows
    (fn [xs]
      (if (empty? xs)
        []
        (reduce (fn [acc [v m]]
                  (if (pos? m)
                    (conj acc [v 1])
                    (throw (ex-info "distinct needs positive multiplicity" {:m m}))))
                []
                xs)))
    trace))

(defn trace-iterate [_ _]
  (throw (ex-info "trace-iterate not implemented" {})))
