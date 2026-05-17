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
   (letfn [(step [state _side msg]
             (case (first msg)
               :frontier (:state (vc/binary-frontier-step state _side (vc/frontier-value msg)))
               state))
           (emit [old-state _state side msg]
             (case (first msg)
               :data [(vc/data (vc/version msg) (vc/rows msg))]
               :frontier (:messages (vc/binary-frontier-step old-state side (vc/frontier-value msg)))
               []))]
     (vc/binary-operator a-ch b-ch {} step emit buf))))

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
   (letfn [(step [state side msg]
             (case (first msg)
               :data (let [rows (ms/consolidate (vc/rows msg))]
                       (update state side add-rows (vc/version msg) rows))
               :frontier (:state (vc/binary-frontier-step state side (vc/frontier-value msg)))
               state))
           (emit [old-state _state side msg]
             (case (first msg)
               :data (let [rows (ms/consolidate (vc/rows msg))
                           left? (= side :a)
                           other (if left? :b :a)]
                       (result-messages
                        (join-delta (vc/version msg) rows (get old-state other) left?)))
               :frontier (:messages (vc/binary-frontier-step old-state side (vc/frontier-value msg)))
               []))]
     (vc/binary-operator a-ch b-ch {:a {} :b {}} step emit buf))))

(defn- remember-input [state version rows]
  (let [trace (add-rows (:input state) version rows)
        todo (c/reduce
              (fn [todo k]
                (c/reduce #(update %1 (f/version-lub version %2) (fnil conj #{}) k)
                          (update todo version (fnil conj #{}) k)
                          (trace-versions trace k)))
              (:todo state)
              (index/key-set rows))]
    (assoc state :input trace :todo todo)))

(defn- output-delta-at [f state version]
  (into []
        (c/mapcat
         (fn [k]
           (let [curr (reconstruct (:input state) k version)
                 prev (reconstruct (:output state) k version)]
             (c/map (fn [[v m]] [[k v] m])
                    (ms/difference (ms/consolidate (f curr))
                                   (ms/consolidate prev)))))
         (get-in state [:todo version]))))

(defn- remember-emitted-output-at [f state version]
  (let [rows (ms/consolidate (output-delta-at f state version))]
    (cond-> (update state :todo dissoc version)
      (seq rows) (update :output add-rows version rows))))

(defn- closed-versions [state frontier]
  (sort (remove #(f/frontier-lte-version? frontier %) (keys (:todo state)))))

(defn- remember-emitted-output [state frontier f]
  (c/reduce (partial remember-emitted-output-at f) state (closed-versions state frontier)))

(defn- emit-closed-versions [state frontier f]
  (for [version (closed-versions state frontier)
        :let [rows (ms/consolidate (output-delta-at f state version))]
        :when (seq rows)]
    (vc/data version rows)))

(defn- advance-reduce-frontier [state frontier]
  (vc/frontier-step state frontier))

(defn reduce
  ([f] (reduce f reduce-buf))
  ([f buf]
   (letfn [(step [state msg]
             (case (first msg)
               :data (remember-input state (vc/version msg) (ms/consolidate (vc/rows msg)))
               :frontier (:state (advance-reduce-frontier
                                   (remember-emitted-output state (vc/frontier-value msg) f)
                                   (vc/frontier-value msg)))
               state))
           (emit [old-state _state msg]
             (case (first msg)
               :frontier (into (vec (emit-closed-versions old-state (vc/frontier-value msg) f))
                               (:messages (advance-reduce-frontier old-state (vc/frontier-value msg))))
               []))]
     (vc/unary-operator {:input {} :output {} :todo {} :out-frontier nil} step emit buf))))

(defn- sum-multiplicities [rows]
  (c/reduce (fn [acc [_ m]] (+ acc (long m))) 0 rows))

(defn count
  ([] (count reduce-buf))
  ([buf]
   (reduce (fn [rows] [[(sum-multiplicities rows) 1]]) buf)))

(defn ingress
  ([] (ingress default-buf))
  ([buf]
   (letfn [(step [state msg]
             (case (first msg)
               :frontier (:state (vc/frontier-step state (f/frontier-extend (vc/frontier-value msg))))
               state))
           (emit [old-state _state msg]
             (case (first msg)
               :data (let [inner (f/version-extend (vc/version msg))
                           rows (vc/rows msg)]
                       [(vc/data inner rows)
                        (vc/data (f/version-apply-step inner 1) (ms/negate rows))])
               :frontier (:messages (vc/frontier-step old-state (f/frontier-extend (vc/frontier-value msg))))
               []))]
     (vc/unary-operator {:out-frontier nil} step emit buf))))

(defn egress
  ([] (egress default-buf))
  ([buf]
   (letfn [(step [state msg]
             (case (first msg)
               :frontier (:state (vc/frontier-step state (f/frontier-truncate (vc/frontier-value msg))))
               state))
           (emit [old-state _state msg]
             (case (first msg)
               :data [(vc/data (f/version-truncate (vc/version msg)) (vc/rows msg))]
               :frontier (:messages (vc/frontier-step old-state (f/frontier-truncate (vc/frontier-value msg))))
               []))]
     (vc/unary-operator {:out-frontier nil} step emit buf))))

(defn- advance-data-version [version step]
  (f/version-apply-step version step))

(defn- close-empty-loop? [empty top]
  (> (c/count (get empty top)) 3))

(defn- candidate-feedback-frontier [state frontier step]
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
           (if (close-empty-loop? empty' top)
             (-> acc
                 (assoc :empty (dissoc empty' top))
                 (assoc :in-flight (dissoc in-flight top))
                 (update :rejected conj elem))
             (-> acc (assoc :empty empty') (update :candidate conj elem)))))))
   (assoc state :candidate [] :rejected [])
   (f/frontier-apply-step frontier step)))

(defn- advance-feedback [state frontier step]
  (let [state' (candidate-feedback-frontier state frontier step)
        extra (for [r (:rejected state') top (keys (:in-flight state'))]
                (f/version-lub r (f/version-extend top)))]
    [(dissoc state' :candidate :rejected)
     (f/frontier (c/concat (:candidate state') extra))]))

(defn- track-in-flight [state version step]
  (let [version' (advance-data-version version step)
        top (f/version-truncate version')]
    (-> state
        (update-in [:in-flight top] (fnil conj #{}) version')
        (update :empty #(if (contains? % top) % (assoc % top #{}))))))

(defn feedback
  ([] (feedback 1 default-buf))
  ([step] (feedback step default-buf))
  ([step buf]
   (letfn [(step-state [state msg]
             (case (first msg)
               :data (track-in-flight state (vc/version msg) step)
               :frontier (let [[state' candidate] (advance-feedback state (vc/frontier-value msg) step)]
                           (:state (vc/frontier-step state' candidate)))
               state))
           (emit [old-state _state msg]
             (case (first msg)
               :data [(vc/data (advance-data-version (vc/version msg) step) (vc/rows msg))]
               :frontier (let [[_ candidate] (advance-feedback old-state (vc/frontier-value msg) step)]
                           (:messages (vc/frontier-step old-state candidate)))
               []))]
     (vc/unary-operator {:out-frontier nil :in-flight {} :empty {}} step-state emit buf))))

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
       ;; The loop starts with no feedback data but with an open inner frontier.
       (a/>!! feedback-in [:frontier #{[0 0]}])
       (a/tap body-mult feedback-source)
       (a/tap body-mult egress-source)
       (s/pipe-to! ((feedback 1 buf) feedback-source) feedback-in)
       ((egress buf) egress-source)))))
