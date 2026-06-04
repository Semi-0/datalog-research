(ns propagators.generic-procedure
  "Propagator-native generic procedures.

  A generic procedure cell is a named-network value. Initialization installs the
  reducer policy/default slots once; method definitions merge compiled branch
  fragments into the same cell."
  (:require [propagators.cells.diff :as diff]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.datastructures.compound-object :as obj]
            [propagators.debugger :as debugger]
            [propagators.dispatch :as dispatch]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.message :refer [message]]
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

(defn- make-method-branch-value
  [predicate-values matcher-value handler-value]
  {:method/predicates (vec predicate-values)
   :method/matcher matcher-value
   :method/handler handler-value})

(defn- make-method-extension
  [method-key branch-value]
  (nb/named-cell-net [[(vector method-tag method-key) branch-value]]))

(defn make-generic-propagator
  "Initialize `generic-id` with fixed v1 select-one policy and `default-id`.

  Returns an installer. The default remains a normal slot in the generic
  procedure value, so later updates to `default-id` merge through the generic
  cell like any other named-network extension."
  [generic-id default-id]
  (fn [n]
    (let [policy-id (ids/new-node-id)
          n0 (nb/install-cell n policy-id select-one-policy-tag select-one-policy-tag)
          [policy-prop n1] ((layered/p:layer policy-slot policy-id generic-id) n0)
          [default-prop n2] ((layered/p:layer default-slot default-id generic-id) n1)]
      [[policy-prop default-prop] n2])))

(defn define-generic-propagator
  "Merge one method branch into an initialized generic procedure cell.

  `predicate-ids` are ordered per-argument predicate closure cells.
  `arg-matcher-id` is a closure cell over predicate result booleans.
  `handler-id` is a closure cell over the filtered arguments."
  [generic-id method-key predicate-ids arg-matcher-id handler-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [predicate-values (mapv #(net/network-cell-strongest network %) predicate-ids)
           matcher-value (net/network-cell-strongest network arg-matcher-id)
           handler-value (net/network-cell-strongest network handler-id)]
       (if (or (apply value/any-unusable-values? predicate-values)
               (value/unusable? matcher-value)
               (value/unusable? handler-value))
         []
         [(message generic-id
                   (make-method-extension
                    method-key
                    (make-method-branch-value predicate-values
                                              matcher-value
                                              handler-value)))])))
   (into (vec predicate-ids) [arg-matcher-id handler-id])
   [generic-id]))

(defn- resolve-cell-or-value
  [network x]
  (if (ids/node-id? x)
    (net/network-cell-strongest network x)
    x))

(defn define-generic-propagator-handler
  "Merge one generic handler into `generic-id`.

  `applicability` is built with `match-cells` or `match-cells-pred`.
  `handler` may be a handler closure value or a cell id containing one."
  ([generic-id applicability handler]
   (define-generic-propagator-handler generic-id
                                      (generated-method-key)
                                      applicability
                                      handler))
  ([generic-id method-key applicability handler]
   (prop/construct-propagator
    (fn [_inputs _outputs network]
      (let [predicate-values (:predicate-closures applicability)
            matcher-value (:matcher-closure applicability)
            handler-value (resolve-cell-or-value network handler)]
        (if (or (apply value/any-unusable-values? predicate-values)
                (value/unusable? matcher-value)
                (value/unusable? handler-value))
          []
          [(message generic-id
                    (make-method-extension
                     method-key
                     (make-method-branch-value predicate-values
                                               matcher-value
                                               handler-value)))])))
    (cond-> []
      (ids/node-id? handler) (conj handler))
    [generic-id])))

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

(defn- generic-methods
  [generic-value]
  (->> (net/net-dict-or-empty generic-value)
       (keep (fn [[slot-key slot-id]]
               (when (method-slot? slot-key)
                 {:method-key (second slot-key)
                  :branch-value (net/network-cell-strongest generic-value slot-id)})))
       (sort-by (comp pr-str method-key))
       (mapv make-method-spec)
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
     :default-value (when-not (value/unusable? generic-value)
                      (obj/slot-value generic-value default-slot))
     :policy-value (when-not (value/unusable? generic-value)
                     (obj/slot-value generic-value policy-slot))
     :methods (when-not (value/unusable? generic-value)
                (generic-methods generic-value))}))

(defn- context-args-usable?
  [{:keys [outer-net arg-ids]}]
  (not (apply value/any-unusable-values?
              (mapv #(net/network-cell-strongest outer-net %) arg-ids))))

(defn- generic-application-ready?
  [{:keys [generic-value default-value policy-value] :as context}]
  (and (not (value/unusable? generic-value))
       (context-args-usable? context)
       (not (value/unusable? default-value))
       (= select-one-policy-tag policy-value)))

(defn- make-application-net
  [{:keys [outer-net arg-ids default-value methods]}]
  (let [result-bank-id (ids/new-node-id)
        default-id (ids/new-node-id)
        reduced-out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (#(reduce (fn [acc id] (nb/copy-cell acc outer-net id)) % arg-ids))
               (nb/install-cell default-id default-value default-value)
               (nb/install-cell reduced-out-id)
               (dispatch/install-result-bank result-bank-id))]
    {:net n0
     :arg-ids arg-ids
     :methods methods
     :result-bank-id result-bank-id
     :default-id default-id
     :reduced-out-id reduced-out-id}))

(defn- generic-reducer-install
  [{:keys [methods result-bank-id default-id reduced-out-id]}]
  (dispatch/reduce-results
   (dispatch/select-one-policy (mapv :method-key methods) default-id)
   result-bank-id
   reduced-out-id))

(defn- build-generic-application
  [context]
  (let [{:keys [net methods arg-ids result-bank-id] :as app}
        (make-application-net context)
        branch-app (install-generic-branches net methods arg-ids result-bank-id)]
    (assoc app
           :net (:net branch-app)
           :branch-prop-ids (:props branch-app)
           :branch-debug (:debug branch-app)
           :reducer-install (generic-reducer-install app))))

(defn- cell-strongest-or-nothing
  [n id]
  (if (contains? (net/net-env n) id)
    (net/network-cell-strongest n id)
    value/nothing))

(defn- report-generic-branches!
  [n branch-debug]
  (when (debugger/enabled?)
    (doseq [{:keys [slot-key
                    predicate-out-ids
                    match-out-id
                    filtered-ids
                    handler-out-id
                    result-bank-id]} branch-debug]
      (let [result-bank (cell-strongest-or-nothing n result-bank-id)]
        (debugger/report!
         :generic/method
         {:method-key slot-key
          :predicate-results (mapv #(cell-strongest-or-nothing n %)
                                   predicate-out-ids)
          :matched? (cell-strongest-or-nothing n match-out-id)
          :filtered-args (mapv #(cell-strongest-or-nothing n %) filtered-ids)
          :handler-result (cell-strongest-or-nothing n handler-out-id)
          :result-bank-value (obj/slot-value result-bank slot-key)})))))

(defn- report-generic-selected!
  [n reduced-out-id]
  (debugger/report!
   :generic/selected
   {:selected-value (cell-strongest-or-nothing n reduced-out-id)}))

(defn- run-generic-application
  [{:keys [net branch-prop-ids branch-debug reducer-install reduced-out-id]}]
  (let [after-branches (nb/run-propagators net branch-prop-ids)
        _ (report-generic-branches! after-branches branch-debug)
        [reducer-props reducer-net] (reducer-install after-branches)]
    (let [after (nb/run-propagators reducer-net reducer-props)]
      (report-generic-selected! after reduced-out-id)
      after)))

(defn- generic-apply-activate
  [generic-id arg-ids out-id]
  (fn [_inputs _outputs outer-net]
    (let [context (make-application-context outer-net generic-id arg-ids)]
      (if-not (generic-application-ready? context)
        []
        (let [{:keys [reduced-out-id] :as app} (build-generic-application context)
              after (run-generic-application app)]
          (diff/diff-cells [reduced-out-id] [out-id] after outer-net))))))

(defn p:apply-generic
  [generic-id arg-ids out-id]
  (prop/construct-propagator
   (generic-apply-activate generic-id (vec arg-ids) out-id)
   (into [generic-id] arg-ids)
   [out-id]))

(defn p:generic-operator
  [generic-id]
  (fn [& node-ids]
    (let [nodes (vec node-ids)]
      (p:apply-generic generic-id (vec (butlast nodes)) (last nodes)))))
