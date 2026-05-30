(ns propagators.datastructures.named-network
  "A fast interface lattice for networks with named dict entries.

  Only `:dict` keys are observable commitments. Unnamed graph/env entries are
  treated as derivable implementation detail."
  (:require [clojure.set :as set]
            [propagators.cells.bool4 :as b]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn named-network? [x]
  (and (net/net? x)
       (seq (net/net-dict-or-empty x))))

(defn- named-keys [n]
  (set (keys (net/net-dict-or-empty n))))

(defn- cell->= [a b]
  (b/>= (cell/cell-strongest a) (cell/cell-strongest b)))

(defn- named-entry->= [a-id a-entry b-id b-entry]
  (cond
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
  (let [a-keys (named-keys a)
        b-keys (named-keys b)]
    (if-not (set/subset? b-keys a-keys)
      false
      (reduce
       b/and
       true
       (map (fn [k]
              (let [a-id (get (net/net-dict-or-empty a) k)
                    b-id (get (net/net-dict-or-empty b) k)]
                (named-entry->= a-id (get (net/net-env a) a-id)
                                b-id (get (net/net-env b) b-id))))
            b-keys)))))

(def named-network-subsume? named-network->=)

(defn- merge-graph [a b]
  (merge (net/net-graph a) (net/net-graph b)))

(defn- merge-entry [a-entry b-entry]
  (cond
    (nil? a-entry) b-entry
    (nil? b-entry) a-entry

    (and (cell/cell? a-entry) (cell/cell? b-entry))
    (let [a-strong (cell/cell-strongest a-entry)
          b-strong (cell/cell-strongest b-entry)
          strongest (cond
                      (= true (b/>= a-strong b-strong)) a-strong
                      (= true (b/>= b-strong a-strong)) b-strong
                      (and (b/bool4? a-strong) (b/bool4? b-strong)) (b/join a-strong b-strong)
                      :else value/contradiction)]
      (cell/cell strongest strongest))

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
        b-dict (net/net-dict-or-empty b)]
    (loop [ks (seq (set/union (set (keys a-dict)) (set (keys b-dict))))
           env (merge (net/net-env a) (net/net-env b))
           dict (merge a-dict b-dict)]
      (if-not ks
        (net/net (merge-graph a b) env dict)
        (let [k (first ks)
              a-id (get a-dict k)
              b-id (get b-dict k)]
          (cond
            (or (nil? a-id) (nil? b-id))
            (recur (next ks) env dict)

            :else
            (let [a-entry (get (net/net-env a) a-id)
                  b-entry (get (net/net-env b) b-id)
                  target-id (get dict k)
                  entry (if (and (not= a-id b-id)
                                 (or (prop/prop? a-entry) (prop/prop? b-entry)))
                          value/contradiction
                          (merge-entry a-entry b-entry))]
              (if (value/contradiction? entry)
                value/contradiction
                (recur (next ks) (assoc env target-id entry) dict)))))))))