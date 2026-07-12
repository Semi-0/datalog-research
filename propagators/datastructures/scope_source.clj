(ns propagators.datastructures.scope-source
  "Scope-source tagged partial information for compiler-2 lexical bindings."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.cells.cell :as cell]
            [propagators.datastructures.compound-object :as obj]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.propagator :as prop])
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

(defn source-descriptor
  [source chain]
  {:scope/id source
   :scope/chain (vec chain)})

(defn- source-id
  [source]
  (if (map? source)
    (:scope/id source)
    source))

(defn- source-chain
  [source]
  (when (map? source)
    (:scope/chain source)))

(defn- stable-scope-object
  [source chain payload dependencies]
  (let [source (source-descriptor source chain)
        dependencies (set dependencies)
        candidate-key [::scope-value source payload dependencies]
        slots {base-layer payload
               source-layer source
               dependencies-layer dependencies}
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
   (stable-scope-object source chain payload #{}))
  ([source closure chain payload]
   (scope-value source chain payload))
  ([source closure chain payload dependencies]
   (stable-scope-object source chain payload dependencies)))

(defn base-value [v] (obj/slot-value v base-layer))
(defn source [v] (obj/slot-value v source-layer))
(defn source-scope [v] (source-id (source v)))
(defn context-chain
  [v]
  (or (source-chain (source v))
      (obj/slot-value v chain-layer)))
(defn closure-scope [v] (last (context-chain v)))
(defn dependencies [v]
  (let [dependencies (obj/slot-value v dependencies-layer)]
    (if (set? dependencies) dependencies #{})))

(defn with-dependencies
  [candidate dependencies]
  (scope-value (source-scope candidate)
               nil
               (context-chain candidate)
               (base-value candidate)
               dependencies))

(defn add-dependencies
  [candidate more]
  (with-dependencies candidate
    (set/union (dependencies candidate) (set more))))

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
       (= (context-chain a) (context-chain b))))

(defn- merge-equivalent-candidates
  [left right]
  (with-dependencies left
    (set/union (dependencies left) (dependencies right))))

(defn- merge-candidate
  [content update]
  (let [existing (candidates content)]
    (cond
      (value/contradiction? existing) value/contradiction
      (not (scope-value? update)) value/contradiction
      (empty? existing) update
      (some #(same-scope-value? % update) existing)
      (let [merged (mapv (fn [candidate]
                           (if (same-scope-value? candidate update)
                             (merge-equivalent-candidates candidate update)
                             candidate))
                         existing)]
        (cond
          (= merged existing) content
          (= 1 (count merged)) (first merged)
          :else merged))
      :else (conj (vec existing) update))))

(defn merge-content
  [content update]
  (let [updates (candidates update)]
    (cond
      (value/contradiction? updates) value/contradiction
      (empty? updates) content
      :else (reduce merge-candidate content updates))))

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
              (with-dependencies
                (first strongest)
                (apply set/union (map dependencies strongest)))
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
               nil
               (context-chain candidate)
               (f (base-value candidate))
               (dependencies candidate)))

(defn unwrap
  [v]
  (if (scope-value? v)
    (base-value v)
    v))

(def p:scope-value
  "Primitive propagator: source + chain + payload -> scope-source candidate."
  (prop/primitive-propagator
   (fn [source chain payload]
     (if (or (value/unusable? source)
             (value/unusable? chain)
             (value/unusable? payload))
       value/nothing
       (scope-value source chain payload)))))

(defn lexical-token
  [lookup-key source chain]
  {:provenance/type :lexical-access
   :lookup/key lookup-key
   :scope/source source
   :scope/chain chain})

(defn adapt-candidates
  "Retarget reducer declarations to one active chain without selecting them."
  [lookup-key candidates chain]
  (mapv (fn [{:keys [scope/source binding]}]
          (scope-value source
                       nil
                       chain
                       binding
                       #{(lexical-token lookup-key source chain)}))
        candidates))

(defn p:adapt-candidates
  [lookup-key candidates-id chain-id out-id]
  ((prop/primitive-propagator
    (fn [candidates chain]
      (if (or (value/unusable? candidates)
              (value/unusable? chain))
        value/nothing
        (adapt-candidates lookup-key (or candidates []) chain))))
   candidates-id chain-id out-id))
