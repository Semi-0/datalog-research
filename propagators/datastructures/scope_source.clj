(ns propagators.datastructures.scope-source
  "Scope-source tagged partial information for compiler-2 lexical bindings."
  (:require [propagators.cells.value :as value]
            [propagators.cells.cell :as cell]
            [propagators.datastructures.compound-object :as obj]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.network :as net])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def base-layer :base)
(def source-layer :scope/source)
(def closure-layer :scope/closure)
(def chain-layer :scope/chain)
(def dependencies-layer :scope/dependencies)

(defn- stable-node-id [& seed]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str seed) StandardCharsets/UTF_8))))

(defn- stable-scope-object
  [source closure chain payload dependencies]
  (let [candidate-key [::scope-value source closure chain payload dependencies]
        slots {base-layer payload
               source-layer source
               closure-layer closure
               chain-layer (vec chain)
               dependencies-layer (set dependencies)}
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

(defn scope-value
  ([source chain payload]
   (scope-value source (last (vec chain)) chain payload #{}))
  ([source closure chain payload]
   (scope-value source closure chain payload #{}))
  ([source closure chain payload dependencies]
   (stable-scope-object source closure chain payload dependencies)))

(defn base-value [v] (obj/slot-value v base-layer))
(defn source-scope [v] (obj/slot-value v source-layer))
(defn closure-scope [v] (obj/slot-value v closure-layer))
(defn context-chain [v] (obj/slot-value v chain-layer))
(defn dependencies [v] (or (obj/slot-value v dependencies-layer) #{}))

(defn scope-value?
  [v]
  (let [base (base-value v)
        chain (context-chain v)]
    (and (some? (source-scope v))
         (vector? chain)
         (not (value/unusable? base)))))

(defn- scope-candidates?
  [v]
  (and (sequential? v)
       (seq v)
       (every? scope-value? v)))

(defn scope-content?
  [v]
  (or (scope-value? v)
      (scope-candidates? v)))

(defn- candidates
  [content]
  (cond
    (value/nothing? content) []
    (scope-value? content) [content]
    (scope-candidates? content) (vec content)
    :else value/contradiction))

(defn- same-scope-value?
  [a b]
  (and (= (base-value a) (base-value b))
       (= (source-scope a) (source-scope b))
       (= (closure-scope a) (closure-scope b))
       (= (context-chain a) (context-chain b))
       (= (dependencies a) (dependencies b))))

(defn merge-content
  [content update]
  (let [existing (candidates content)]
    (cond
      (value/contradiction? existing) value/contradiction
      (not (scope-value? update)) value/contradiction
      (empty? existing) update
      (some #(same-scope-value? % update) existing) content
      :else (conj (vec existing) update))))

(defn content-candidates
  [content]
  (let [existing (candidates content)]
    (if (value/contradiction? existing)
      []
      existing)))

(defn- source-rank
  [candidate]
  (let [source (source-scope candidate)
        chain (context-chain candidate)]
    (->> chain
         (map-indexed vector)
         (filter (fn [[_idx scope]] (= source scope)))
         last
         first)))

(defn strongest-value
  [content]
  (let [existing (candidates content)]
    (cond
      (value/contradiction? existing) value/contradiction
      (empty? existing) value/nothing
      :else
      (let [ranked (keep (fn [candidate]
                           (when-let [rank (source-rank candidate)]
                             [rank candidate]))
                         existing)]
        (if (empty? ranked)
          value/nothing
          (let [max-rank (apply max (map first ranked))
                strongest (map second (filter #(= max-rank (first %)) ranked))
                bases (set (map base-value strongest))]
            (if (= 1 (count bases))
              (first strongest)
              value/contradiction)))))))

(defn retarget
  [candidate closure chain]
  (scope-value (source-scope candidate)
               closure
               chain
               (base-value candidate)
               (dependencies candidate)))

(defn map-base
  [candidate f]
  (scope-value (source-scope candidate)
               (closure-scope candidate)
               (context-chain candidate)
               (f (base-value candidate))
               (dependencies candidate)))

(defn unwrap
  [v]
  (if (scope-value? v)
    (base-value v)
    v))
