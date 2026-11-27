(ns propagators.prototype-2.church
  "Church-style cons / car / cdr via visitor-network + bi-sync.

  (cons a b) → (lambda (visitor) (visitor a b))

  The pair is a compound whose input is a visitor-network cell. When that
  visitor is a Visitor, expand bi-syncs visitor ports with a and b.
  A non-Visitor logs and leaves the compound unbuilt."
  (:require [propagators.prototype-2.construct :as ctor]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.primitives :as prim]
            [propagators.prototype-2.session :as session]
            [propagators.prototype-2.types :as t]))

(defrecord Visitor [a b])

(defn visitor
  "Visitor-network value: two cell ids to bi-sync with cons's a and b."
  [a-port b-port]
  (->Visitor a-port b-port))

(defn visitor? [x]
  (instance? Visitor x))

(defn- log-non-visitor
  [visitor-in value]
  (println "cons: visitor cell" (pr-str visitor-in)
           "is not a Visitor — skip expand; value=" (pr-str value)))

(defn install-cons
  "Curried Church cons as a compound over a visitor-network input.

  Returns
    {:network … :visitor … :applied … :a a :b b :pair pair-id :list list-cell}

  `list` cell holds the Church cons itself (lazy: a/b/visitor) — that *is*
  the p-list node. Terminate by telling `b` `empty-list` (`'()`), not `nothing`.
  Nest by telling `b` another Church cons (from an inner install-cons :list).

  Tell `visitor` a (visitor a-port b-port); evaluation expands to
  bi-sync a <-> a-port and b <-> b-port. Non-Visitor → log, no build."
  ([network a b]
   (install-cons network a b (net/new-cell-id "pair")))
  ([network a b pair-name]
   (let [visitor-in (net/new-cell-id "visitor")
         applied (net/new-cell-id "applied")
         list-cell (net/new-cell-id "list")
         handle (plist/church-cons a b visitor-in)
         expand
         (fn [n]
           (let [vis (net/cell-value n visitor-in)]
             (if-not (visitor? vis)
               (do (log-non-visitor visitor-in vis)
                   n)
               (-> n
                   (net/ensure-cells [(:a vis) (:b vis) applied])
                   (prim/install-bi-sync a (:a vis))
                   (prim/install-bi-sync b (:b vis))
                   (ev/write-cell applied :applied)))))]
     (let [network (net/ensure-cells network [a b visitor-in applied list-cell])
           network (net/assoc-value network list-cell (t/cell handle))
           network (ctor/pending-compound-constructor
                    network expand [visitor-in] [applied] pair-name)]
       {:network network
        :visitor visitor-in
        :applied applied
        :a a
        :b b
        :pair pair-name
        :list list-cell}))))

(defn- ready-new-props
  "Prop ids installed after `before` whose inputs are already concrete."
  [network before]
  (into []
        (keep (fn [[id entry]]
                (when (and (t/propagator-value? entry)
                           (not (contains? before id))
                           (net/inputs-ready? network (:input-ids entry)))
                  id)))
        (net/network-value network)))

(defn apply-visitor
  "Apply pair (a, b) to a visitor-network: bi-sync a/b with visitor ports.

  Non-Visitor → log and return [[] network] with no new topology."
  [network a b vis]
  (if-not (visitor? vis)
    (do (log-non-visitor :apply-visitor vis)
        [[] network])
    (let [before (net/network-value network)
          network (-> network
                      (net/ensure-cells [a b (:a vis) (:b vis)])
                      (prim/install-bi-sync a (:a vis))
                      (prim/install-bi-sync b (:b vis)))]
      [(ready-new-props network before) network])))

(defn install-car
  "Church car: feed cons a visitor that bi-syncs `a` to `result`.

  Two arities:
    [network visitor-in result] — tell the cons visitor cell (first apply)
    [network a b result]        — direct apply-visitor (re-apply OK)

  Returns [tasks network]."
  ([network visitor-in result]
   (let [sink (net/new-cell-id "cdr-sink")
         network (net/ensure-cells network [visitor-in result sink])]
     (session/tell network visitor-in (visitor result sink))))
  ([network a b result]
   (let [sink (net/new-cell-id "cdr-sink")]
     (apply-visitor network a b (visitor result sink)))))

(defn install-cdr
  "Church cdr: feed cons a visitor that bi-syncs `b` to `result`."
  ([network visitor-in result]
   (let [sink (net/new-cell-id "car-sink")
         network (net/ensure-cells network [visitor-in result sink])]
     (session/tell network visitor-in (visitor sink result))))
  ([network a b result]
   (let [sink (net/new-cell-id "car-sink")]
     (apply-visitor network a b (visitor sink result)))))

(defn cons-then
  "Curried helper: ((cons-then network) a) b) → install-cons map."
  [network]
  (fn [a]
    (fn [b]
      (install-cons network a b))))
