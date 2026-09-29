(ns propagators.datastructures.support-collection
  "TTMS (Temporary Truth Maintenance System) evidence collection and projection.
  Retains non-dominated supported observations. See propagators/doc/ttms.md."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.timestamp :as timestamp]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.network :as net])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(s/def ::base any?)
(s/def ::observation
  (s/and (s/keys :req-un [::base ::support/support])
         #(= #{:base :support} (set (keys %)))))
(s/def ::evidence (s/coll-of ::observation :kind set?))

(defn content? [x]
  (and (map? x) (contains? x :support/observations)))

(defn- observation [x]
  (let [layers (cond
                 (net/net? x) (set (obj/public-slot-keys x))
                 (map? x) (set (keys x))
                 :else #{})]
    (if (and (= #{:base :support} layers)
             (support/support? (datum/layer-value x :support)))
      {:base (datum/layer-value x :base)
       :support (datum/layer-value x :support)}
      (throw (ex-info "Supported observations require exactly base and support layers"
                      {:datum x :layers layers})))))

(defn content
  "Normalize one two-layer datum. Preserve its base without unwrapping it."
  [datum]
  {:support/observations #{(observation datum)}})

(defn- observations [x]
  (cond
    (value/nothing? x) #{}
    (and (content? x)
         (= #{:support/observations} (set (keys x)))
         (set? (:support/observations x)))
    (into #{} (map observation) (:support/observations x))
    :else (throw (ex-info "Expected supported collection content" {:content x}))))

(defn- bottom? [observation]
  (and (value/nothing? (:base observation)) (empty? (:support observation))))

(defn- dominates? [newer older]
  (if (bottom? older)
    (not (bottom? newer))
    (support/dominates? (:support newer) (:support older))))

(defn- undominated [observations]
  (into #{}
        (remove (fn [older] (some #(dominates? % older) observations)))
        observations))

(defn merge-content
  "Discard dominated observations; retain incomparable and same-version conflicts.
  Retractions can dominate older active values. Nothing is empty."
  [content update]
  {:support/observations
   (undominated (set/union (observations content) (observations update)))})

(defn- source-ranks [entries]
  (into {} (map (fn [{:keys [source timestamp]}]
                 [source (timestamp/time-rank timestamp)]))
        (support/join entries)))

(defn- current-observation? [current-ranks observation]
  ;; Compare each source's newest version within this observation. Incompatible
  ;; versions inside a still-current observation remain attached, not repaired.
  (every? (fn [[source rank]] (= rank (get current-ranks source)))
          (source-ranks (:support observation))))

(defn- slot-id [layer]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (str "support-collection/projection/" layer)
               StandardCharsets/UTF_8))))

(defn- projection [base support]
  (reduce-kv
   (fn [network layer v]
     (let [id (slot-id layer)]
       (-> network
           (net/assoc-net-cell id (cell/cell v v))
           (net/assoc-net-node id (graph/blank-node))
           (net/assoc-net-dict-entry layer id))))
   (net/net-with-dict net/empty-net {:slot-index {:base #{} :support #{}}})
   {:base base :support support}))

(defn strongest-value
  "Project current payloads and support. Base merging is supplied by the caller;
  this collection does not own base-domain rules or computation readiness."
  [content merge-base]
  (let [retained (undominated (observations content))]
    (if (empty? retained)
      value/nothing
      (let [frontier (apply support/join (map :support retained))
            ranks (source-ranks frontier)
            current (sort-by pr-str (filter #(current-observation? ranks %) retained))
            base (reduce merge-base value/nothing (map :base current))
            ;; The frontier detects freshness; it is not a result dependency.
            ;; A stale observation's premises must not contaminate a current one.
            ;; With no current observation, nothing still carries the frontier
            ;; so an earlier downstream computation can be invalidated.
            supports (if (seq current)
                       (apply support/combine (map :support current))
                       frontier)]
        (projection base supports)))))
