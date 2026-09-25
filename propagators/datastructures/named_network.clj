(ns propagators.datastructures.named-network
  "A fast interface lattice for networks with named dict entries.

  Only `:dict` keys are observable commitments. Unnamed graph/env entries are
  treated as derivable implementation detail."
  (:require [clojure.set :as set]
            [propagators.cells.bool4 :as b]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]))

(defn named-network? [x]
  (and (net/net? x)
       (seq (net/net-dict-or-empty x))))

(defn- named-keys [n]
  (set (keys (net/net-dict-or-empty n))))

(declare named-network->=)

(defn- value->= [a b]
  (if (and (named-network? a) (named-network? b))
    (named-network->= a b)
    (b/>= a b)))

(defn- cell->= [a b]
  (value->= (cell/cell-strongest a) (cell/cell-strongest b)))

(declare metadata->=)

(defn- map->= [a b]
  (if-not (every? #(contains? a %) (keys b))
    false
    (reduce
     b/and
     true
     (map (fn [k] (metadata->= (get a k) (get b k)))
          (keys b)))))

(defn- metadata->= [a b]
  (cond
    (= a b) true
    (and (set? a) (set? b)) (set/superset? a b)
    (and (map? a) (map? b)) (map->= a b)
    :else false))

(defn- named-entry->= [a-id a-entry b-id b-entry]
  (cond
    (or (not (ids/node-id? a-id))
        (not (ids/node-id? b-id)))
    (metadata->= a-id b-id)

    (and (cell/cell? a-entry) (cell/cell? b-entry))
    (cell->= a-entry b-entry)

    (and (prop/prop? a-entry) (prop/prop? b-entry))
    (if (= a-id b-id) true b/contradiction)

    (and (= a-id b-id) (= a-entry b-entry))
    true

    :else
    b/contradiction))

(defn named-network->=
  "Bool4 preorder over named network interfaces.

  `a >= b` when every named commitment in `b` is present in `a`, and every
  corresponding named entry in `a` subsumes the entry in `b`."
  [a b]
  (let [a-dict (net/net-dict-or-empty a)
        b-dict (net/net-dict-or-empty b)]
    (if-not (every? #(contains? a-dict %) (keys b-dict))
      false
      (reduce
       b/and
       true
       (map (fn [k]
              (let [a-id (get a-dict k)
                    b-id (get b-dict k)]
                (named-entry->= a-id (get (net/net-env a) a-id)
                                b-id (get (net/net-env b) b-id))))
            (keys b-dict))))))

(def named-network-subsume? named-network->=)

(defn- merge-graph [a b]
  (merge (net/net-graph a) (net/net-graph b)))

(defn- merge-metadata [a b]
  (cond
    (nil? a) b
    (nil? b) a
    (and (map? a) (map? b)) (merge-with merge-metadata a b)
    (and (set? a) (set? b)) (set/union a b)
    (= a b) a
    :else b))

(defn- scope-source-content?
  [x]
  (boolean
   (when-let [scope-content?
              (requiring-resolve
               'propagators.datastructures.scope-source/scope-content?)]
     (scope-content? x))))

(defn- scope-source-mergeable?
  [x]
  (or (value/nothing? x)
      (scope-source-content? x)))

(defn- merge-scope-source-entry
  [a-entry b-entry]
  (let [a-content (cell/cell-content a-entry)
        b-content (cell/cell-content b-entry)]
    (when (and (scope-source-mergeable? a-content)
               (scope-source-mergeable? b-content)
               (or (scope-source-content? a-content)
                   (scope-source-content? b-content)))
      (let [merge-content
            (requiring-resolve
             'propagators.datastructures.scope-source/merge-content)
            strongest-value
            (requiring-resolve
             'propagators.datastructures.scope-source/strongest-value)
            merged (merge-content a-content b-content)]
        (cell/cell merged (strongest-value merged))))))

(defn- merge-entry [a-entry b-entry]
  (cond
    (nil? a-entry) b-entry
    (nil? b-entry) a-entry

    (and (cell/cell? a-entry) (cell/cell? b-entry))
    (or (merge-scope-source-entry a-entry b-entry)
        (let [a-strong (cell/cell-strongest a-entry)
              b-strong (cell/cell-strongest b-entry)
              strongest (cond
                          (= true (value->= a-strong b-strong)) a-strong
                          (= true (value->= b-strong a-strong)) b-strong
                          (and (b/bool4? a-strong) (b/bool4? b-strong)) (b/join a-strong b-strong)
                          :else value/contradiction)]
          (cell/cell strongest strongest)))

    (= a-entry b-entry)
    a-entry

    :else
    value/contradiction))

(defn join
  "Join two named networks by unioning named commitments.

  Same named cell entries are joined by Bool4 strongest values. Same named
  propagators are only joinable by identical internal id."
  [a b]
  (let [a-dict (net/net-dict-or-empty a)
        b-dict (net/net-dict-or-empty b)
        shared-keys (if (< (count a-dict) (count b-dict))
                      (filter #(contains? b-dict %) (keys a-dict))
                      (filter #(contains? a-dict %) (keys b-dict)))]
    (loop [ks (seq shared-keys)
           env (merge (net/net-env a) (net/net-env b))
           dict (merge-with merge-metadata a-dict b-dict)]
      (if-not ks
        (net/net (merge-graph a b)
                 env
                 dict
                 (relationship/merge-relationships
                  (net/net-relationship a)
                  (net/net-relationship b)))
        (let [k (first ks)
              a-id (get a-dict k)
              b-id (get b-dict k)]
          (let [a-entry (get (net/net-env a) a-id)
                b-entry (get (net/net-env b) b-id)
                target-id (get dict k)
                entry (if (and (not= a-id b-id)
                               (or (prop/prop? a-entry) (prop/prop? b-entry)))
                        value/contradiction
                        (merge-entry a-entry b-entry))]
            (if (value/contradiction? entry)
              value/contradiction
              (recur (next ks) (assoc env target-id entry) dict))))))))
