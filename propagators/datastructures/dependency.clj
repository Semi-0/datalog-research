(ns propagators.datastructures.dependency
  "Dependency-tagged layered values for compiler-2 contextual operators."
  (:require [clojure.set :as set]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.network :as net])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def base-layer :base)
(def sources-layer :dependency/sources)

(defn- stable-node-id [& seed]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str seed) StandardCharsets/UTF_8))))

(defn dependency-value
  [payload sources]
  (let [sources (set sources)
        candidate-key [::dependency-value payload sources]
        slots {base-layer payload
               sources-layer sources}
        slot-index (zipmap (keys slots) (repeat #{}))]
    (reduce-kv
     (fn [n slot-key slot-value]
       (let [slot-id (stable-node-id candidate-key slot-key)]
         (-> n
             (net/assoc-net-cell slot-id (cell/cell slot-value slot-value))
             (net/assoc-net-node slot-id (graph/blank-node))
             (net/assoc-net-dict-entry slot-key slot-id))))
     (net/net-with-dict net/empty-net {:slot-index slot-index})
     slots)))

(defn base-value [v]
  (obj/slot-value v base-layer))

(defn sources [v]
  (let [sources (obj/slot-value v sources-layer)]
    (if (set? sources) sources #{})))

(defn dependency-value?
  [v]
  (let [base (base-value v)
        sources (obj/slot-value v sources-layer)]
    (and (set? sources)
         (not (value/unusable? base)))))

(defn- dependency-candidates?
  [v]
  (and (sequential? v)
       (seq v)
       (every? dependency-value? v)))

(defn dependency-content?
  [v]
  (or (dependency-value? v)
      (dependency-candidates? v)))

(defn- candidates
  [content]
  (cond
    (value/nothing? content) []
    (dependency-value? content) [content]
    (dependency-candidates? content) (vec content)
    :else value/contradiction))

(defn- same-base?
  [candidates]
  (= 1 (count (set (map base-value candidates)))))

(defn- merged-dependency-value
  [candidates]
  (dependency-value (base-value (first candidates))
                    (apply set/union (map sources candidates))))

(defn merge-content
  [content update]
  (let [existing (candidates content)]
    (cond
      (value/contradiction? existing) value/contradiction
      (not (dependency-value? update)) value/contradiction
      (empty? existing) update
      (not (same-base? (conj (vec existing) update))) value/contradiction
      :else (merged-dependency-value (conj (vec existing) update)))))

(defn strongest-value
  [content]
  (let [existing (candidates content)]
    (cond
      (value/contradiction? existing) value/contradiction
      (empty? existing) value/nothing
      (not (same-base? existing)) value/contradiction
      :else (merged-dependency-value existing))))

(defn unwrap
  [v]
  (if (dependency-value? v)
    (base-value v)
    v))
