(ns propagators.datastructures.support
  "TTMS (Temporary Truth Maintenance System) support-set operations.
  A set is conjunctive: every source is required. Evidence/projection live in
  support-collection. See propagators/doc/ttms.md for the contract and laws."
  (:require [clojure.spec.alpha :as s]
            [propagators.datastructures.timestamp :as timestamp]
            [propagators.ids :as ids]))

(s/def ::source ids/node-id?)
(s/def ::timestamp any?)
(s/def ::premises-status #{:active :retracted})
(s/def ::premise
  (s/and (s/keys :req-un [::source ::timestamp ::premises-status])
         #(= #{:source :timestamp :premises-status} (set (keys %)))))
(s/def ::support (s/coll-of ::premise :kind set?))

(defn entry? [x]
  (and (map? x)
       (= #{:source :timestamp :premises-status} (set (keys x)))
       (ids/node-id? (:source x))
       (contains? #{:active :retracted} (:premises-status x))))

(defn support? [x]
  (and (set? x) (every? entry? x)))

(defn- checked [support]
  (if (support? support)
    support
    (throw (ex-info "Expected a set of source/timestamp/premises-status records"
                    {:support support}))))

(defn- insert-current [by-source entry]
  (let [source (:source entry)
        current (get by-source source)]
    (if (seq current)
      (let [ordering (compare (timestamp/time-rank (:timestamp entry))
                              (timestamp/time-rank (:timestamp (first current))))]
        (cond
          (pos? ordering) (assoc by-source source #{entry})
          (neg? ordering) by-source
          :else (update by-source source conj entry)))
      (assoc by-source source #{entry}))))

(defn join
  "Join current support states by source. Equal-version conflicts are retained.
  Do not use this normalization to validate a computation's input versions."
  [& supports]
  (into #{} cat
        (vals (reduce insert-current {} (mapcat checked supports)))))

(defn combine
  "Conjoin a computation's supports without concealing incompatible versions.
  Unlike source-state join, deriving a value must retain every input premise."
  [& supports]
  (into #{} (mapcat checked supports)))

(defn compatible?
  "Check the whole input tuple BEFORE joining; shared sources must agree on
  timestamp and status. Stops at the first conflict."
  [& supports]
  (not= ::conflict
        (reduce
         (fn [seen entry]
           (let [source (:source entry)
                 version (select-keys entry [:timestamp :premises-status])]
             (if (contains? seen source)
               (if (= (get seen source) version)
                 seen
                 (reduced ::conflict))
               (assoc seen source version))))
         {} (mapcat checked supports))))

(defn covers?
  "Every old premise is covered by the same injection cell at an equal compatible
  or newer timestamp. Do not normalize incompatible tuples before comparing."
  [newer older]
  (let [newer (checked newer)
        older (checked older)]
    (every?
     (fn [old]
       (boolean
        (some
         (fn [new]
           (and (= (:source new) (:source old))
                (let [order (compare (timestamp/time-rank (:timestamp new))
                                     (timestamp/time-rank (:timestamp old)))]
                  (cond
                    (pos? order) true
                    (zero? order) (= (:premises-status new) (:premises-status old))
                    :else false))))
         newer)))
     older)))

(defn dominates?
  "Compatible support strictly extends/advances old support, or coherently
  replaces an incompatible tuple. Equal support never resolves payload conflict."
  [newer older]
  (and (compatible? newer)
       (covers? newer older)
       (or (not (covers? older newer))
           (not (compatible? older)))))

(s/fdef covers? :args (s/cat :newer ::support :older ::support) :ret boolean?)
(s/fdef dominates? :args (s/cat :newer ::support :older ::support) :ret boolean?)

(defn unusable?
  "Retraction and conflicting evidence make a supported datum unusable to
  concrete consumers. They do not gate its base procedure or support transport."
  [support]
  (or (not (support? support))
      (boolean (some #(= :retracted (:premises-status %)) support))
      (not (compatible? support))))
