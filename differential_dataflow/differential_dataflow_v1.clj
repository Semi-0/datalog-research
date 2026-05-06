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


(defn trace-reduce [f trace]
  (vec
   (peek (reduce (fn [[indexed-input indexed-output chunks] next-patch]
                   (let [[i-in i-out r] (merge-collection f indexed-input indexed-output next-patch)]
                     [i-in i-out (conj chunks r)]))
                 [index/empty-index index/empty-index []]
                 trace))))

(def ^{:doc "Alias of `trace-reduce` (historical name)."}
  *trace-reduce trace-reduce)

(defn- make-count-aggregator
  "Returns per-key `f` for `trace-reduce`: sum raw multiplicities → published row `[[total 1]]`."
  []
  (fn [vals]
    [[(reduce #(+ %1 (second %2)) 0 vals) 1]]))

(defn trace-count [trace]
  (trace-reduce (make-count-aggregator) trace))

(defn- make-sum-aggregator
  "Returns per-key `f` for `trace-reduce`: Σ(value × multiplicity) → published row `[[sum 1]]`."
  []
  (fn [vals]
    [[(reduce #(+ %1 (* (first %2) (second %2))) 0 vals) 1]]))

(defn trace-sum [trace]
  (trace-reduce (make-sum-aggregator) trace))

(defn- make-extremum-aggregator
  "Returns the `f` given to `trace-reduce`: collapse each key's value multiset to one min/max row.
  `pick` is binary compare-and-replace (e.g. min / max). `label` tags multiplicity errors."
  [label pick]
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
            1]])))))

(defn trace-min [trace]
  (trace-reduce (make-extremum-aggregator "min" (fn [acc v] (if (< v acc) v acc))) trace))

(defn trace-max [trace]
  (trace-reduce (make-extremum-aggregator "max" (fn [acc v] (if (> v acc) v acc))) trace))

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
