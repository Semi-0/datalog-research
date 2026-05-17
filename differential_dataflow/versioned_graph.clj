(ns differential-dataflow.versioned-graph
  (:refer-clojure :exclude [map filter concat reduce count iterate])
  (:require [clojure.core :as c]
            [clojure.core.async :as a]
            [clojure.set :as set]
            [differential-dataflow.frontier :as f]
            [differential-dataflow.index :as index]
            [differential-dataflow.multiset :as ms]
            [differential-dataflow.stream-ops :as s]
            [differential-dataflow.versioned-core :as vc]))

(def ^:private default-buf 8)
(def ^:private reduce-buf 16)

(defn map
  ([f] (map f default-buf))
  ([f buf] (s/on-data (ms/map f) buf)))

(defn filter
  ([pred] (filter pred default-buf))
  ([pred buf] (s/on-data (ms/filter pred) buf)))

(defn negate
  ([] (negate default-buf))
  ([buf] (s/on-data ms/negate buf)))

(defn consolidate
  ([] (consolidate default-buf))
  ([buf] (s/on-data ms/consolidate buf)))

(defn concat
  ([a-ch b-ch] (concat a-ch b-ch default-buf))
  ([a-ch b-ch buf]
   (vc/binary a-ch b-ch {}
              {:data (fn [state _side version rows]
                       [state [(vc/data version rows)]])}
              buf)))

(defn- add-row [trace version [[k v] mult]]
  (update-in trace [k version] (fnil conj []) [v mult]))

(defn- add-rows [trace version rows]
  (c/reduce #(add-row %1 version %2) trace rows))

(defn- trace-versions [trace k]
  (keys (get trace k {})))

(defn- reconstruct [trace k version]
  (ms/consolidate
   (mapcat (fn [[t rows]] (when (f/version-lte? t version) rows))
           (get trace k {}))))

(defn- result-messages [results]
  (for [[version rows] (sort-by key results)
        :when (seq rows)]
    (vc/data version rows)))

(defn- join-delta [version rows trace left?]
  (->> rows
       (c/reduce
        (fn [out [[k v] mult]]
          (c/reduce-kv
           (fn [out t vals]
             (let [version' (f/version-lub version t)
                   joined (c/mapv (fn [[w n]]
                                    [[k (if left? [v w] [w v])] (* mult n)])
                                  vals)]
               (update out version' ms/append joined)))
           out
           (get trace k {})))
        {})
       (c/map (fn [[t xs]] [t (ms/consolidate xs)]))
       (into {})))

(defn join
  ([a-ch b-ch] (join a-ch b-ch reduce-buf))
  ([a-ch b-ch buf]
   (vc/binary
    a-ch b-ch {:a {} :b {}}
    {:data (fn [state side version rows]
             (let [rows (ms/consolidate rows)
                   left? (= side :a)
                   own (if left? :a :b)
                   other (if left? :b :a)
                   results (join-delta version rows (get state other) left?)]
               [(update state own add-rows version rows)
                (result-messages results)]))}
    buf)))

(defn- add-input [state version rows]
  (let [trace (add-rows (:input state) version rows)
        todo (c/reduce
              (fn [todo k]
                (c/reduce #(update %1 (f/version-lub version %2) (fnil conj #{}) k)
                          (update todo version (fnil conj #{}) k)
                          (trace-versions trace k)))
              (:todo state)
              (index/key-set rows))]
    (assoc state :input trace :todo todo)))

(defn- reduce-delta [f input output version ks]
  (into []
        (c/mapcat
         (fn [k]
           (let [curr (reconstruct input k version)
                 prev (reconstruct output k version)]
             (c/map (fn [[v m]] [[k v] m])
                    (ms/difference (ms/consolidate (f curr))
                                   (ms/consolidate prev)))))
         ks)))

(defn- flush-version [f [state messages] version]
  (let [rows (reduce-delta f (:input state) (:output state) version (get-in state [:todo version]))
        state' (-> state
                   (update :output add-rows version rows)
                   (update :todo dissoc version))]
    [state'
     (cond-> messages
       (seq rows) (conj (vc/data version (ms/consolidate rows))))]))

(defn- flush-closed [state frontier f]
  (let [closed (sort (remove #(f/frontier-lte-version? frontier %) (keys (:todo state))))]
    (c/reduce (partial flush-version f) [state []] closed)))

(defn reduce
  ([f] (reduce f reduce-buf))
  ([f buf]
   (vc/stateful
    {:input {} :output {} :todo {} :out-frontier nil}
    {:data (fn [state version rows]
             [(add-input state version (ms/consolidate rows)) []])
     :frontier (fn [{:keys [out-frontier] :as state} frontier]
                 (let [[state' messages] (flush-closed state frontier f)
                       [out messages'] (vc/emit-frontier out-frontier frontier)]
                   [(assoc state' :out-frontier out) (into messages messages')]))}
    buf)))

(defn- sum-multiplicities [rows]
  (c/reduce (fn [acc [_ m]] (+ acc (long m))) 0 rows))

(defn count
  ([] (count reduce-buf))
  ([buf]
   (reduce (fn [rows] [[(sum-multiplicities rows) 1]]) buf)))

(defn ingress
  ([] (ingress default-buf))
  ([buf]
   (vc/unary
    (fn [version rows]
      (let [inner (f/version-extend version)]
        [(vc/data inner rows)
         (vc/data (f/version-apply-step inner 1) (ms/negate rows))]))
    f/frontier-extend
    buf)))

(defn egress
  ([] (egress default-buf))
  ([buf]
   (vc/unary
    (fn [version rows] [(vc/data (f/version-truncate version) rows)])
    f/frontier-truncate
    buf)))

(defn- feedback-frontier [state frontier step]
  (c/reduce
   (fn [{:keys [in-flight empty candidate rejected] :as acc} elem]
     (let [top (f/version-truncate elem)
           flights (get in-flight top #{})]
       (if (seq flights)
         (let [closed (set (c/filter #(f/version-lt? % elem) flights))]
           (-> acc
               (update :candidate conj elem)
               (assoc-in [:in-flight top] (set/difference flights closed))))
         (let [empty' (update empty top (fnil conj #{}) elem)]
           (if (<= (c/count (get empty' top)) 3)
             (-> acc (assoc :empty empty') (update :candidate conj elem))
             (-> acc
                 (assoc :empty (dissoc empty' top))
                 (assoc :in-flight (dissoc in-flight top))
                 (update :rejected conj elem)))))))
   (assoc state :candidate [] :rejected [])
   (f/frontier-apply-step frontier step)))

(defn- advance-feedback [state frontier step]
  (let [state' (feedback-frontier state frontier step)
        extra (for [r (:rejected state') top (keys (:in-flight state'))]
                (f/version-lub r (f/version-extend top)))]
    [(dissoc state' :candidate :rejected)
     (f/frontier (c/concat (:candidate state') extra))]))

(defn feedback
  ([] (feedback 1 default-buf))
  ([step] (feedback step default-buf))
  ([step buf]
   (vc/stateful
    {:out-frontier nil :in-flight {} :empty {}}
    {:data (fn [state version rows]
             (let [version' (f/version-apply-step version step)
                   top (f/version-truncate version')]
               [(-> state
                    (update-in [:in-flight top] (fnil conj #{}) version')
                    (update :empty #(if (contains? % top) % (assoc % top #{}))))
                [(vc/data version' rows)]]))
     :frontier (fn [{:keys [out-frontier] :as state} frontier]
                 (let [[state' candidate] (advance-feedback state frontier step)
                       [out messages] (vc/emit-frontier out-frontier candidate)]
                   [(assoc state' :out-frontier out) messages]))}
    buf)))

(defn iterate
  ([f] (iterate f default-buf))
  ([f buf]
   (fn [in]
     (let [feedback-in (a/chan buf)
           entered (concat ((ingress buf) in) feedback-in buf)
           body-out (f entered)
           body-mult (a/mult body-out)
           feedback-source (a/chan buf)
           egress-source (a/chan buf)]
       (a/tap body-mult feedback-source)
       (a/tap body-mult egress-source)
       (s/pipe-to! ((feedback 1 buf) feedback-source) feedback-in)
       ((egress buf) egress-source)))))
