(ns propagators.datastructures.behavior-algebra
  "Pure algebra over retained behavior history records.

  Behavior reducers own retention. The functions here only transform or
  synchronize already-retained temporal records. Histories are idempotent
  compound-object collections, not weighted multisets."
  (:refer-clojure :exclude [consolidate])
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]))

(def infinity :infinity)

(defn point-record
  [t v]
  (obj/compound-object {:at t :value v}))

(defn interval-record
  [from to v]
  (obj/compound-object {:from from :to to :value v}))

(defn constant-record
  [from v]
  (interval-record from infinity v))

(defn point-record?
  [record]
  (some? (obj/slot-value record :at)))

(defn interval-record?
  [record]
  (and (some? (obj/slot-value record :from))
       (some? (obj/slot-value record :to))))

(defn record-value
  [record]
  (obj/slot-value record :value))

(defn record-start
  [record]
  (if (point-record? record)
    (obj/slot-value record :at)
    (obj/slot-value record :from)))

(defn record-end
  [record]
  (if (point-record? record)
    (obj/slot-value record :at)
    (obj/slot-value record :to)))

(defn- time-rank
  [t]
  (if (= infinity t)
    Long/MAX_VALUE
    t))

(defn- record-kind-rank
  [record]
  (if (point-record? record) 0 1))

(defn- record-sort-key
  [record]
  [(time-rank (record-start record))
   (record-kind-rank record)
   (time-rank (record-end record))
   (pr-str (record-value record))])

(defn history-records
  "Return retained records sorted by temporal position."
  [history]
  (let [history-object (obj/compound-object history)]
    (if (value/contradiction? history-object)
      value/contradiction
      (->> (obj/public-slot-keys history-object)
           (map #(obj/slot-value history-object %))
           (sort-by record-sort-key)
           vec))))

(defn records->history
  "Build a compound-object history from temporal records.

  Slot keys are implementation details. History-aware operators should inspect
  records through `history-records`, not infer semantics from slot keys."
  [records]
  (obj/compound-object
   (into {}
         (map-indexed
          (fn [idx record]
            [[(record-start record) idx] record]))
         (sort-by record-sort-key records))))

(def empty-history
  (records->history []))

(defn- record-signature
  [record]
  (cond
    (point-record? record)
    [:point (obj/slot-value record :at) (record-value record)]

    (interval-record? record)
    [:interval
     (obj/slot-value record :from)
     (obj/slot-value record :to)
     (record-value record)]

    :else
    [:invalid record]))

(defn- signature->record
  [signature]
  (let [[kind a b c] signature]
    (case kind
      :point (point-record a b)
      :interval (interval-record a b c)
      value/contradiction)))

(defn consolidate
  "Remove duplicate temporal facts.

  This is set-style, idempotent consolidation. Repeating the same temporal fact
  does not make it stronger."
  [history]
  (let [records (history-records history)]
    (if (value/contradiction? records)
      value/contradiction
      (->> records
           (map record-signature)
           distinct
           (map signature->record)
           records->history))))

(defn history-union
  "Idempotently union histories by temporal fact."
  [& histories]
  (let [record-groups (map history-records histories)]
    (if (some value/contradiction? record-groups)
      value/contradiction
      (consolidate (records->history (mapcat identity record-groups))))))

(defn history-negate-values
  "Apply numeric negation to payload values while preserving time."
  [history]
  (let [records (history-records history)]
    (if (value/contradiction? records)
      value/contradiction
      (records->history
       (mapv (fn [record]
               (let [negated (if (value/unusable? (record-value record))
                               (record-value record)
                               (- (record-value record)))]
                 (cond
                   (point-record? record)
                   (point-record (obj/slot-value record :at) negated)

                   (interval-record? record)
                   (interval-record (obj/slot-value record :from)
                                    (obj/slot-value record :to)
                                    negated)

                   :else value/contradiction)))
             records)))))

(defn history-map-values
  "Apply `f` to each retained payload value while preserving temporal shape."
  [f history]
  (let [records (history-records history)]
    (if (value/contradiction? records)
      value/contradiction
      (records->history
       (mapv (fn [record]
               (let [mapped (f (record-value record))]
                 (cond
                   (point-record? record)
                   (point-record (obj/slot-value record :at) mapped)

                   (interval-record? record)
                   (interval-record (obj/slot-value record :from)
                                    (obj/slot-value record :to)
                                    mapped)

                   :else value/contradiction)))
             records)))))

(defn- min-time
  [a b]
  (cond
    (= infinity a) b
    (= infinity b) a
    :else (min a b)))

(defn- max-time
  [a b]
  (cond
    (= infinity a) b
    (= infinity b) a
    :else (max a b)))

(defn- before?
  [a b]
  (cond
    (= infinity b) (not= infinity a)
    (= infinity a) false
    :else (< a b)))

(defn- at-or-before?
  [a b]
  (or (= a b) (before? a b)))

(defn- interval-contains-point?
  [interval t]
  (and (at-or-before? (obj/slot-value interval :from) t)
       (before? t (obj/slot-value interval :to))))

(defn- temporal-overlap
  [a b]
  (cond
    (and (point-record? a) (point-record? b))
    (when (= (obj/slot-value a :at) (obj/slot-value b :at))
      {:kind :point :at (obj/slot-value a :at)})

    (and (point-record? a) (interval-record? b))
    (let [t (obj/slot-value a :at)]
      (when (interval-contains-point? b t)
        {:kind :point :at t}))

    (and (interval-record? a) (point-record? b))
    (let [t (obj/slot-value b :at)]
      (when (interval-contains-point? a t)
        {:kind :point :at t}))

    (and (interval-record? a) (interval-record? b))
    (let [from (max-time (obj/slot-value a :from)
                         (obj/slot-value b :from))
          to (min-time (obj/slot-value a :to)
                       (obj/slot-value b :to))]
      (when (before? from to)
        {:kind :interval :from from :to to}))

    :else nil))

(defn- normalize-join-spec
  [join-spec]
  (if (fn? join-spec)
    {:combine join-spec}
    join-spec))

(defn- keyed-match?
  [{:keys [key-fn left-key-fn right-key-fn]} left-value right-value]
  (cond
    key-fn (= (key-fn left-value) (key-fn right-value))
    (or left-key-fn right-key-fn)
    (= ((or left-key-fn identity) left-value)
       ((or right-key-fn identity) right-value))
    :else true))

(defn- joined-record
  [join-spec left right]
  (let [spec (normalize-join-spec join-spec)
        {:keys [combine]} spec
        left-value (record-value left)
        right-value (record-value right)]
    (when (and combine
               (keyed-match? spec left-value right-value))
      (when-let [overlap (temporal-overlap left right)]
        (let [value (combine left-value right-value)]
          (case (:kind overlap)
            :point (point-record (:at overlap) value)
            :interval (interval-record (:from overlap)
                                       (:to overlap)
                                       value)))))))

(defn history-join
  "Synchronize two histories over temporal overlap.

  `join-spec` may be a two-argument combiner function, or a map:

  `{:combine f}` joins every overlapping pair.
  `{:key-fn k :combine f}` joins only when `(k left-value)` equals
  `(k right-value)`.
  `{:left-key-fn lk :right-key-fn rk :combine f}` supports asymmetric keys.

  Point events join only at the same tick. Intervals join on half-open overlap
  `[from, to)`. Points join with intervals only when the point lies inside the
  interval. Repeated equal join results are idempotent."
  [join-spec left-history right-history]
  (let [left-records (history-records left-history)
        right-records (history-records right-history)]
    (if (or (value/contradiction? left-records)
            (value/contradiction? right-records))
      value/contradiction
      (consolidate
       (records->history
        (keep (fn [[left right]]
                (joined-record join-spec left right))
              (for [left left-records
                    right right-records]
                [left right])))))))

(defn history-add-values
  "Add numeric values by temporal synchronization."
  [left-history right-history]
  (history-join + left-history right-history))

(defn history-join-all
  "Synchronize all histories over shared temporal overlap.

  `combine` receives one payload value per input history. A point output exists
  only when every input has a point at the same tick or an interval covering
  that tick. Interval output exists only over the intersection shared by every
  input interval/point combination.
  "
  [combine histories]
  (cond
    (empty? histories) empty-history
    (= 1 (count histories)) (history-map-values combine (first histories))
    :else
    (let [initial (history-map-values vector (first histories))
          joined (reduce (fn [acc history]
                           (history-join
                            (fn [values value]
                              (conj values value))
                            acc
                            history))
                         initial
                         (rest histories))]
      (if (value/contradiction? joined)
        value/contradiction
        (history-map-values #(apply combine %) joined)))))
