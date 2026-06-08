(ns propagators.generic-procedure
  "Propagator-native generic procedures.

  A generic procedure cell is a named-network value. Initialization installs the
  reducer policy/default slots once; method definitions merge compiled branch
  slots into the same cell."
  (:require [propagators.application :as application]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.datastructures.compound-object :as obj]
            [propagators.dispatch :as dispatch]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def ^:private default-slot :generic/default)
(def ^:private policy-slot :generic/policy)
(def ^:private method-tag :generic/method)
(def ^:private select-one-policy-tag :select-one)

(defn- generated-method-key
  []
  [:generic/handler (ids/new-node-id)])

(def predicate-closure closure/primitive-closure)
(def match-args-closure closure/primitive-closure)
(def all-args-match-closure
  (match-args-closure (fn [& predicate-results] (every? true? predicate-results))))

(defn handler-closure
  "Build a guarded handler closure for generic method bodies."
  [f]
  (closure/guarded-primitive-closure f))

(defn match-cells
  "Applicability object for generic handlers.

  `predicate-closures` are ordered per argument. `matcher-closure` receives the
  predicate results and decides whether the handler is applicable."
  ([predicate-closures]
   (match-cells predicate-closures all-args-match-closure))
  ([predicate-closures matcher-closure]
   {:predicate-closures (vec predicate-closures)
    :matcher-closure matcher-closure}))

(defn match-cells-pred
  "Applicability object from ordinary predicate functions."
  [& predicates]
  (match-cells (mapv predicate-closure predicates)))

(defn make-generic-propagator
  "Initialize `generic-id` with fixed v1 select-one policy and `default-id`.

  Returns an installer. The default remains a normal slot in the generic
  procedure value, so later updates to `default-id` merge through the generic
  cell like any other named-network extension."
  [generic-id default-id]
  (fn [n]
    (let [policy-id (ids/new-node-id)
          n0 (nb/install-cell n policy-id select-one-policy-tag select-one-policy-tag)
          [policy-prop n1] ((obj/p:slot policy-slot policy-id generic-id) n0)
          [default-prop n2] ((obj/p:slot default-slot default-id generic-id) n1)]
      [[policy-prop default-prop] n2])))

(defn- value-cell
  [n v]
  (let [id (ids/new-node-id)]
    [id (nb/install-cell n id v v)]))

(defn- predicate-vector-installer
  [predicate-ids predicates-id]
  (apply (prop/primitive-propagator vector)
         (conj (vec predicate-ids) predicates-id)))

(defn- install-method-attachment
  [n generic-id method-key predicate-ids matcher-id handler-id]
  (let [predicates-id (ids/new-node-id)
        branch-id (ids/new-node-id)
        n0 (-> n
               (nb/install-cell predicates-id)
               (nb/install-cell branch-id))
        [predicates-prop n1] ((predicate-vector-installer predicate-ids predicates-id) n0)
        [predicates-slot-prop n2] ((obj/p:slot :method/predicates predicates-id branch-id) n1)
        [matcher-slot-prop n3] ((obj/p:slot :method/matcher matcher-id branch-id) n2)
        [handler-slot-prop n4] ((obj/p:slot :method/handler handler-id branch-id) n3)
        [method-slot-prop n5] ((obj/p:slot (vector method-tag method-key)
                                           branch-id
                                           generic-id)
                               n4)]
    [[predicates-prop
      predicates-slot-prop
      matcher-slot-prop
      handler-slot-prop
      method-slot-prop]
     n5]))

(defn- define-generic-propagator*
  "Merge one method branch into an initialized generic procedure cell.

  `predicate-ids` are ordered per-argument predicate closure cells.
  `arg-matcher-id` is a closure cell over predicate result booleans.
  `handler-id` is a closure cell over the filtered arguments."
  [generic-id method-key predicate-ids arg-matcher-id handler-id]
  (fn [n]
    (install-method-attachment n
                               generic-id
                               method-key
                               predicate-ids
                               arg-matcher-id
                               handler-id)))

(defn define-generic-propagator
  "Merge one method branch into an initialized generic procedure cell.

  `predicate-ids` are ordered per-argument predicate closure cells.
  `arg-matcher-id` is a closure cell over predicate result booleans.
  `handler-id` is a closure cell over the filtered arguments."
  [generic-id predicate-ids arg-matcher-id handler-id]
  (define-generic-propagator* generic-id
                              (generated-method-key)
                              predicate-ids
                              arg-matcher-id
                              handler-id))

(defn- define-generic-propagator-handler*
  "Merge one generic handler into `generic-id`.

  `applicability` is built with `match-cells` or `match-cells-pred`.
  `handler` may be a handler closure value or a cell id containing one."
  [generic-id method-key applicability handler]
  (fn [n]
    (let [[predicate-ids n1]
          (reduce
           (fn [[ids acc] predicate-value]
             (let [[predicate-id acc'] (value-cell acc predicate-value)]
               [(conj ids predicate-id) acc']))
           [[] n]
           (:predicate-closures applicability))
          [matcher-id n2] (value-cell n1 (:matcher-closure applicability))
          [handler-id n3] (if (ids/node-id? handler)
                            [handler n2]
                            (value-cell n2 handler))]
      (install-method-attachment n3
                                 generic-id
                                 method-key
                                 predicate-ids
                                 matcher-id
                                 handler-id))))

(defn define-generic-propagator-handler
  "Merge one generic handler into `generic-id`.

  `applicability` is built with `match-cells` or `match-cells-pred`.
  `handler` may be a handler closure value or a cell id containing one."
  [generic-id applicability handler]
  (define-generic-propagator-handler* generic-id
                                      (generated-method-key)
                                      applicability
                                      handler))

(defn- method-slot?
  [slot-key]
  (and (vector? slot-key)
       (= method-tag (first slot-key))
       (= 2 (count slot-key))))

(defn- method-key [m] (:method-key m))
(defn- method-predicates [m] (:predicates m))
(defn- method-matcher [m] (:matcher m))
(defn- method-handler [m] (:handler m))

(defn- make-method-spec
  [{:keys [method-key branch-value]}]
  {:method-key method-key
   :predicates (obj/slot-value branch-value :method/predicates)
   :matcher (obj/slot-value branch-value :method/matcher)
   :handler (obj/slot-value branch-value :method/handler)})

(defn- compound-slot-present?
  [compound-value slot-key]
  (let [compound-net (obj/compound-object compound-value)]
    (and (not (value/contradiction? compound-net))
         (some? (net/network-dict-entry compound-net slot-key)))))

(defn- complete-method-spec?
  [{:keys [predicates matcher handler]}]
  (and (vector? predicates)
       (not (apply value/any-unusable-values? predicates))
       (some? matcher)
       (not (value/unusable? matcher))
       (some? handler)
       (not (value/unusable? handler))))

(defn- generic-methods
  [generic-value]
  (->> (net/net-dict-or-empty generic-value)
       (keep (fn [[slot-key slot-id]]
               (when (method-slot? slot-key)
                 {:method-key (second slot-key)
                  :branch-value (net/network-cell-strongest generic-value slot-id)})))
       (sort-by (comp pr-str method-key))
       (mapv make-method-spec)
       (filter complete-method-spec?)
       vec))

(defn- install-method-closures
  [n method]
  (let [predicate-ids (vec (repeatedly (count (method-predicates method)) ids/new-node-id))
        matcher-id (ids/new-node-id)
        handler-id (ids/new-node-id)]
    {:net (-> n
              (#(reduce (fn [acc [id v]] (nb/install-cell acc id v v))
                        %
                        (map vector predicate-ids (method-predicates method))))
              (nb/install-cell matcher-id (method-matcher method) (method-matcher method))
              (nb/install-cell handler-id (method-handler method) (method-handler method)))
     :predicate-ids predicate-ids
     :matcher-id matcher-id
     :handler-id handler-id}))

(defn- install-method-branch
  [n method arg-ids result-bank-id]
  (let [{:keys [net predicate-ids matcher-id handler-id]} (install-method-closures n method)
        branch (dispatch/p:matched-handler-branch*
                (method-key method)
                predicate-ids
                matcher-id
                handler-id
                arg-ids
                result-bank-id)
        [prop-ids net'] ((:installer branch) net)]
    {:net net'
     :props prop-ids
     :debug branch}))

(defn- install-generic-branches
  [n method-specs arg-ids result-bank-id]
  (reduce
   (fn [{:keys [net props debug]} method-spec]
     (let [branch (install-method-branch net method-spec arg-ids result-bank-id)]
       {:net (:net branch)
        :props (into props (:props branch))
        :debug (conj debug (:debug branch))}))
   {:net n :props [] :debug []}
   method-specs))

(defn- make-application-context
  [outer-net generic-id arg-ids]
  (let [generic-value (net/network-cell-strongest outer-net generic-id)]
    {:outer-net outer-net
     :generic-value generic-value
     :arg-ids arg-ids
     :default-present? (when-not (value/unusable? generic-value)
                         (compound-slot-present? generic-value default-slot))
     :default-value (when-not (value/unusable? generic-value)
                      (obj/slot-value generic-value default-slot))
     :policy-present? (when-not (value/unusable? generic-value)
                        (compound-slot-present? generic-value policy-slot))
     :policy-value (when-not (value/unusable? generic-value)
                     (obj/slot-value generic-value policy-slot))
     :methods (when-not (value/unusable? generic-value)
                (generic-methods generic-value))}))

(defn- context-args-usable?
  [{:keys [outer-net arg-ids]}]
  (application/cells-usable? outer-net arg-ids))

(defn- generic-application-ready?
  [{:keys [generic-value default-present? default-value policy-present? policy-value] :as context}]
  (and (not (value/unusable? generic-value))
       (context-args-usable? context)
       default-present?
       (not (value/contradiction? default-value))
       policy-present?
       (= select-one-policy-tag policy-value)))

(defn- generic-reducer-install
  [{:keys [methods result-bank-id default-id reduced-out-id]}]
  (dispatch/reduce-results
   (dispatch/select-one-policy (mapv :method-key methods) default-id)
   result-bank-id
   reduced-out-id))

(defn- build-generic-application
  [{:keys [outer-net arg-ids default-value methods]}]
  (let [default-id (ids/new-node-id)]
    (application/build-branch-application
     {:cell-specs (conj (mapv #(application/copied-cell outer-net %) arg-ids)
                        (application/value-cell default-id default-value))
      :install-branches (fn [n {:keys [result-bank-id]}]
                          (let [branch-app
                                (install-generic-branches n
                                                          methods
                                                          arg-ids
                                                          result-bank-id)]
                            {:net (:net branch-app)
                             :branch-prop-ids (:props branch-app)
                             :branch-debug (:debug branch-app)
                             :methods methods
                             :arg-ids arg-ids
                             :default-id default-id}))
      :reducer-install generic-reducer-install
      :trace (fn [{:keys [branch-debug reduced-out-id]}]
               {:trace/type :generic
                :branches branch-debug
                :reduced-out-id reduced-out-id})})))

(defn- generic-apply-activate
  [generic-id arg-ids out-id]
  (fn [_inputs _outputs outer-net]
    (let [context (make-application-context outer-net generic-id arg-ids)]
      (if-not (generic-application-ready? context)
        []
        (let [{:keys [reduced-out-id] :as app} (build-generic-application context)
              after (application/run-reduced-application app)]
          (application/diff-reduced-output outer-net after reduced-out-id out-id))))))

(defn p:apply-generic
  [generic-id arg-ids out-id]
  (prop/construct-propagator
   (generic-apply-activate generic-id (vec arg-ids) out-id)
   (into [generic-id] arg-ids)
   [out-id]))

(defn apply-generic-value
  "Apply an already-realized generic procedure value to plain argument values.

  This runs the generic application in a temporary network and returns the
  selected output value. The temporary network has no cell-protocol dict keys, so
  merge/strongest protocol hooks do not recursively dispatch while this helper
  is evaluating a protocol generic."
  [generic-value arg-values]
  (let [generic-id (ids/new-node-id)
        arg-ids (vec (repeatedly (count arg-values) ids/new-node-id))
        out-id (ids/new-node-id)
        n0 (reduce nb/install-cell net/empty-net (into [generic-id out-id] arg-ids))
        n1 (nb/seed-cell n0 generic-id generic-value)
        n2 (reduce (fn [acc [id v]] (nb/seed-cell acc id v))
                   n1
                   (map vector arg-ids arg-values))
        [apply-prop n3] ((p:apply-generic generic-id arg-ids out-id) n2)
        n4 (nb/run-propagators n3 [apply-prop])]
    (net/network-cell-strongest n4 out-id)))

(defn p:generic-operator
  [generic-id]
  (fn [& node-ids]
    (let [nodes (vec node-ids)]
      (p:apply-generic generic-id (vec (butlast nodes)) (last nodes)))))
