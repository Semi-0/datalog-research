(ns propagators.prototype-2.support-tag
  "TTMS-style support on L-2 observation lists (no Cell envelope).

  Reactive value  ::= Church p-list of observations (newest at car)
  Observation     ::= (cons base support)   ; support = host premise-set in a cell

  Concurrency gate = `propagators.datastructures.support/compatible?` before derive;
  result support   = `combine`. Retraction = cons a newer tip (extension).

  Project: lattice frontier via `front-add` / `tms-join` / `tms-front-reduce`
  (C/A/I — incremental dataflow + async partial merge). `tms-project` is the
  tip view. DDB nogoods are *not* built here; see amb + doc/prototype-2-ddb-rx.md."
  (:require [propagators.datastructures.support :as support]
            [propagators.prototype-2.church :as church]
            [propagators.prototype-2.construct :as ctor]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.types :as t]))

(defn premise
  "TTMS premise record."
  ([source timestamp]
   (premise source timestamp :active))
  ([source timestamp status]
   {:source source
    :timestamp timestamp
    :premises-status status}))

(defn stamp
  "Singleton support set for `source` at `timestamp`."
  ([source timestamp]
   (stamp source timestamp :active))
  ([source timestamp status]
   #{(premise source timestamp status)}))

(def compatible? support/compatible?)
(def combine support/combine)
(def covers? support/covers?)
(def dominates? support/dominates?)
(def unusable? support/unusable?)

(defn install-observation
  "Build observation cell `(cons base-cell support-cell)`. Returns `{:network :cell}`."
  [network base-cell support-cell]
  (let [{:keys [network list]} (church/pair-cells network base-cell support-cell)]
    {:network network :cell list}))

(defn observation-from-values
  "Host base + support-set → observation cell. Returns `{:network :cell}`."
  [network base support-set]
  (let [[base-c network] (church/fresh-cell network base "obs-base")
        [sup-c network] (church/fresh-cell network support-set "obs-sup")]
    (install-observation network base-c sup-c)))

(defn reactive-from-observations
  "Host observation cells (newest first) → reactive list cell. Returns `{:network :list}`."
  [network obs-cells]
  (church/list-from-cells network (vec obs-cells)))

(defn extend-tip
  "Cons `obs-cell` onto reactive list `rx-cell`. Returns `{:network :list}`."
  [network rx-cell obs-cell]
  (church/list-from-cells network [obs-cell] rx-cell))

(defn- obs-pair
  "Read `[base support]` from an observation cell, or nil."
  [network obs-cell]
  (let [v (net/cell-value network obs-cell)]
    (when (plist/church-cons? v)
      [(net/cell-value network (:a v))
       (net/cell-value network (:b v))])))

(defn tip
  "Car of reactive list → observation cell id, or nil if empty/open."
  [network rx-cell]
  (let [v (net/cell-value network rx-cell)]
    (cond
      (t/nothing? v) nil
      (plist/empty-list? v) nil
      (plist/church-cons? v) (:a v)
      :else nil)))

(defn tip-base
  [network rx-cell]
  (when-let [obs (tip network rx-cell)]
    (first (obs-pair network obs))))

(defn tip-support
  [network rx-cell]
  (when-let [obs (tip network rx-cell)]
    (second (obs-pair network obs))))

(defn base-of
  "Base value of an observation cell."
  [network obs-cell]
  (first (obs-pair network obs-cell)))

(defn support-of
  "Support set of an observation cell."
  [network obs-cell]
  (second (obs-pair network obs-cell)))

(defn- collect-observations
  "Walk reactive spine → vector of [base support] (newest first)."
  [network rx-cell]
  (loop [id rx-cell
         acc []]
    (let [v (net/cell-value network id)]
      (cond
        (or (t/nothing? v) (plist/empty-list? v)) acc
        (plist/church-cons? v)
        (let [pair (obs-pair network (:a v))]
          (if pair
            (recur (:b v) (conj acc pair))
            acc))
        :else acc))))

(defn- obs-dominates?
  "True when observation a’s support strictly dominates b’s."
  [[_ sa] [_ sb]]
  (and (support/support? sa) (support/support? sb) (dominates? sa sb)))

(defn- obs-equivalent?
  "Same base and support — join keeps one (idempotent)."
  [[ba sa] [bb sb]]
  (and (= ba bb) (= sa sb)))

(defn front-add
  "Lattice assimilate: insert `obs` into undominated frontier (vector).

  Drop members `obs` dominates; drop `obs` if dominated or duplicate.
  Incomparable observations are both kept — commutative / associative join."
  [front obs]
  (let [front (vec front)]
    (cond
      (some #(or (obs-dominates? % obs) (obs-equivalent? % obs)) front)
      front
      :else
      (conj (filterv #(not (obs-dominates? obs %)) front) obs))))

(defn frontier-join
  "Join two frontiers (partial reduce states). Semilattice: C / A / I."
  [f1 f2]
  (reduce front-add (vec f1) f2))

(defn tms-join
  "Join two observations into a frontier (unary case of `frontier-join`)."
  [a b]
  (frontier-join [a] [b]))

(defn- as-frontier
  "Normalize cached projection to a frontier vector of observations."
  [best]
  (cond
    (nil? best) []
    (and (vector? best) (empty? best)) []
    (and (vector? best) (vector? (first best))) best
    :else [best]))

(defn tms-step
  "Incremental dataflow step: assimilate `cand` into frontier `best`.

  `best` may be a single observation `[base support]` or a frontier vector.
  Returns a frontier vector. Prefer this over order-biased single-tip merge."
  [best cand]
  (front-add (as-frontier best) cand))

(defn tms-reduce
  "Fold `f` over observations (thesis cell merge as pull / incremental dataflow)."
  [f acc obs]
  (reduce f acc obs))

(defn tms-front-reduce
  "Reduce observation stream → undominated frontier (async-safe aggregate)."
  [obs]
  (tms-reduce front-add [] obs))

(defn tms-project
  "Tip view of `tms-front-reduce`: one undominated observation, or nil.

  When several are incomparable, returns the first frontier member (stable
  relative to assimilate order). The *content* for async merge is the frontier
  (`tms-front-reduce` / `frontier-join`), not this tip alone."
  [obs]
  (first (tms-front-reduce obs)))

(defn project-tip
  "Undominated tip from a reactive spine via lattice front-reduce.

  Returns `[base support]` or nil. See `tms-front-reduce` for full frontier."
  [network rx-cell]
  (tms-project (collect-observations network rx-cell)))

(defn project-frontier
  "Full undominated frontier from a reactive spine."
  [network rx-cell]
  (tms-front-reduce (collect-observations network rx-cell)))

(defn tms-merge-obs
  "Assimilate `obs` into projected frontier of `rx-cell` (full re-fold)."
  [network rx-cell obs-pair]
  (tms-front-reduce (cons obs-pair (collect-observations network rx-cell))))

(defn tms-step-incremental
  "Amortized merge: assimilate newer tip into cached frontier / single tip.
  Returns a frontier vector."
  [prior cand]
  (tms-step prior cand))

;; ---------------------------------------------------------------------------
;; L-2 primitives — bridge TTMS checks into cells
;; ---------------------------------------------------------------------------

(defn install-compatible?-prop
  "When both supports concrete, write `(compatible? sa sb)` → `out`."
  [network a b out]
  (ctor/pending-propagator-constructor
   network
   (fn [sa sb]
     (boolean (and (support/support? sa)
                   (support/support? sb)
                   (compatible? sa sb))))
   [[a b] out]))

(defn install-combine-support-prop
  "When both supports concrete, write `(combine sa sb)` → `out`."
  [network a b out]
  (ctor/pending-propagator-constructor
   network
   (fn [sa sb]
     (if (and (support/support? sa) (support/support? sb))
       (combine sa sb)
       t/nothing))
   [[a b] out]))

(defn install-dominates?-prop
  "When both supports concrete, write `(dominates? sa sb)` → `out`."
  [network a b out]
  (ctor/pending-propagator-constructor
   network
   (fn [sa sb]
     (boolean (and (support/support? sa)
                   (support/support? sb)
                   (dominates? sa sb))))
   [[a b] out]))
