(ns propagators.prototype-2.p-list
  "Church-style propagator lists (lazy cons) + completeness predicates.

  A p-list value is either:
    empty-list ('())  — concrete terminator
    church-cons       — lazy cons: {:a :b :visitor} (same ports as install-cons)

  nothing on the cdr spine means partial (unknown); '() means complete.
  No separate Pair type — the list node *is* the Church cons (visitor + a/b)."
  (:require [propagators.prototype-2.network :as net]
            [propagators.prototype-2.types :as t]))

(def empty-list
  "Concrete list terminator — not `nothing`, not `nil`."
  '())

(defn empty-list?
  "True for the empty-list sentinel (`'()`)."
  [x]
  (= x empty-list))

(defn church-cons
  "Lazy Church cons as a list node: car/cdr cells + visitor cell.

  car/cdr stay unbound until a visitor is applied (bi-sync) — no eager pair."
  [a b visitor]
  {:church/cons true :a a :b b :visitor visitor})

(defn church-cons?
  "True for a Church cons list node (from `church-cons` / `install-cons`)."
  [x]
  (boolean (and (map? x) (:church/cons x))))

(defn p-list-value?
  "True when `x` is empty-list or a Church cons."
  [x]
  (or (empty-list? x) (church-cons? x)))

(defn p-list?
  "True when cell `id` holds empty-list or a Church cons."
  [network id]
  (p-list-value? (net/cell-value network id)))

(defn- spine-status
  "Walk cdr spine from cell `id`.

  Returns:
    :complete — ends at empty-list; every car along the way is concrete
    :partial  — hit nothing (unknown) before a proper end
    :not-list — malformed (neither list shape nor nothing)"
  [network id]
  (let [v (net/cell-value network id)]
    (cond
      (t/nothing? v)
      :partial

      (empty-list? v)
      :complete

      (church-cons? v)
      (let [car-v (net/cell-value network (:a v))]
        (if (t/nothing? car-v)
          :partial
          (recur network (:b v))))

      :else
      :not-list)))

(defn p-list-completed?
  "True when the spine from `id` is a finished Church list: cars concrete, ends in '().

  Distinct from `chain-complete?` (edge-chain origins); this is list-shape completeness."
  [network id]
  (= :complete (spine-status network id)))

(defn p-list-partial?
  "True when the spine from `id` is a Church list so far but still unknown somewhere
  (a car or cdr cell is `nothing` before empty-list)."
  [network id]
  (= :partial (spine-status network id)))
