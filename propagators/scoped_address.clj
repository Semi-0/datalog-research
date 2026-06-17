(ns propagators.scoped-address
  "Shared scoped cell address shape for dispatchable environment refs."
  (:refer-clojure :exclude [ref]))

(def dispatch-tags #{:env/scope :env/ref :env/cell-ref})
(def ref-kinds #{:cell :slot :env :frame})

(defn scope-ref [scope] [:env/scope scope])
(defn name-ref [scope name] [:env/ref scope name])
(defn cell-ref [scope local-id] [:env/cell-ref scope local-id])

(defn ref
  "Map-shaped scoped address IR.

  `:ref/path` is the stable lowering path. For current vector-key
  compatibility, cell refs store the local cell id in that path slot.
  "
  [{:ref/keys [scope path name kind] :as m}]
  (when-not (contains? ref-kinds kind)
    (throw (ex-info "unknown scoped ref kind" {:ref m})))
  {:ref/scope scope
   :ref/path path
   :ref/name name
   :ref/kind kind})

(defn scope-ref-ir [scope]
  (ref {:ref/scope scope
        :ref/kind :env}))

(defn name-ref-ir [scope name]
  (ref {:ref/scope scope
        :ref/name name
        :ref/kind :cell}))

(defn cell-ref-ir [scope local-id]
  (ref {:ref/scope scope
        :ref/path local-id
        :ref/kind :cell}))

(defn ref?
  [x]
  (and (map? x)
       (contains? x :ref/scope)
       (contains? x :ref/kind)
       (contains? ref-kinds (:ref/kind x))))

(defn vector-address?
  [x]
  (and (vector? x)
       (contains? dispatch-tags (first x))
       (> (count x) 1)))

(defn address?
  [x]
  (or (vector-address? x)
      (ref? x)))

(defn address-scope
  [x]
  (cond
    (vector-address? x) (second x)
    (ref? x) (:ref/scope x)
    :else nil))

(defn ref->address
  "Lower scoped-address IR to the current vector-key dispatch shape."
  [x]
  (cond
    (vector-address? x)
    x

    (not (ref? x))
    (throw (ex-info "not a scoped address" {:address x}))

    (= :env (:ref/kind x))
    (scope-ref (:ref/scope x))

    (:ref/name x)
    (name-ref (:ref/scope x) (:ref/name x))

    (= :cell (:ref/kind x))
    (cell-ref (:ref/scope x) (:ref/path x))

    :else
    (throw (ex-info "cannot lower scoped ref to vector key" {:ref x}))))

(defn address->ref
  "Lift current vector-key dispatch shape into scoped-address IR."
  [x]
  (cond
    (ref? x)
    x

    (and (vector-address? x) (= :env/scope (first x)))
    (scope-ref-ir (second x))

    (and (vector-address? x) (= :env/ref (first x)))
    (name-ref-ir (second x) (nth x 2))

    (and (vector-address? x) (= :env/cell-ref (first x)))
    (cell-ref-ir (second x) (nth x 2))

    :else
    (throw (ex-info "cannot lift scoped address" {:address x}))))
