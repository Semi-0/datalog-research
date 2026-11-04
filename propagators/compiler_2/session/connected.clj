(ns propagators.compiler-2.session.connected
  "Connected immutable declarations. No resource, worker, or host dependency."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.datastructures.compound-object :as object]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur :as gur]
            [propagators.network :as net]))

(def payload data/payload)

;; Immutable data lists are TTMS bases, not compiler-owned open list topology.
;; Keep their empty scalar distinct from the compiler's list interface marker.
(def empty-list :lain.data/empty)

(defn- primitive-content [content]
  (let [observations (or (:support/observations content) (:membership/observations content))
        observation (when (= 1 (count observations)) (first observations))
        base (:base observation)
        constant? (and observation (empty? (:support observation))
                       (empty? (:membership observation))
                       (empty? (:support/states content))
                       (empty? (:membership/states content)))]
    (cond
      ;; Plain scalar constants need no evidence container. Custom structures
      ;; always stay in TTMS, even when they have no premises.
      (and constant? (not (coll? base))) base
      (or (collection/content? content) (membership/content? content)) content
      (coll? content) (collection/content {:base content :support #{}})
      :else content)))

(defn primitive
  "One pure primitive propagator. Custom results occupy the TTMS base layer;
  scalar constants remain primitive values. Use the standard support algebra."
  [name f]
  (let [activate (operator/operator-activate (basis/primitive-operator f))]
    (operator/propagator-operator
     {:name name
      :activate
      (fn [network inputs [output] context]
        (mapv #(update % :value primitive-content)
              (activate network context inputs output)))})))

(def declarations-key ::declarations)
(def interfaces-key ::interfaces)
(def pending ::pending)

(defn freeze
  "Resolve a finite connected data value. Unknown fields keep a declaration pending.
  Callables and lexical references are opaque. This is serialization, not selection."
  ([network v] (freeze network v #{}))
  ([network v seen]
   (let [v (payload (data/payload v))]
     (cond
       (value/unusable? v) pending
       (object/accessor-network? v)
       (if (contains? seen v) pending
           (let [fields (into {} (map (fn [k] [k (freeze network (data/slot-value network v k) (conj seen v))]))
                              (object/accessor-slot-keys v))]
             (if (some #{pending} (vals fields)) pending fields)))
       :else v))))

(defn thaw [v]
  (if (and (map? v) (not (record? v)))
    (object/as-accessor-network (update-vals v thaw)) v))

(defn connector [kind]
  (operator/propagator-operator
   {:name kind
    :activate
    (fn [_ arguments [output] _context]
      {:effects [(gur/bind-name [declarations-key] output
                                {:kind kind :arguments arguments :receipt output})]})}))

(defn export-operator
  "Declare a named external interface to a cell reference supplied by Lain.
  Registration retains argument identities; unresolved exports remain pending."
  []
  (operator/propagator-operator
   {:name 'session:export
    :activate
    (fn [_ arguments [output] _context]
      (when-not (= 4 (count arguments))
        (throw (ex-info "session:export expects family, identity, kind and cell reference" {})))
      {:effects [(gur/bind-name [interfaces-key] output arguments)]})}))

(defn interfaces [network]
  (reduce
   (fn [exports arguments]
     (let [[family identity kind cell :as values]
           (mapv #(freeze network (net/network-cell-strongest network %)) arguments)]
       (if (some #{pending} values) exports
           (let [key [family identity] descriptor {:kind kind :cell cell}]
             (when (and (contains? exports key) (not= descriptor (get exports key)))
               (throw (ex-info "Conflicting connected session interface" {:interface key})))
             (assoc exports key descriptor)))))
   {} (vals (get (net/network-dict-entry network [:network :name-bindings]) [interfaces-key]))))

(defn declarations [network]
  (->> (get (net/network-dict-entry network [:network :name-bindings]) [declarations-key])
       vals
       (sort-by (comp str :receipt))
       (keep (fn [{:keys [arguments] :as declaration}]
               (let [values (mapv #(freeze network (net/network-cell-strongest network %)) arguments)]
                 (when-not (some #{pending} values)
                   (assoc declaration :values values)))))))
