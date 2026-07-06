(ns propagators.generic-procedure.application
  (:require [propagators.application :as application]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.dispatch :as dispatch]
            [propagators.generic-procedure.constants :as constants]
            [propagators.generic-procedure.materialize :as materialize]
            [propagators.generic-procedure.methods :as methods]
            [propagators.gur.accumulating.runner.tasks :as runner-tasks]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-cache :as cache]
            [propagators.propagator :as prop]))

(def ^:dynamic *retained-apply-cache* nil)

(defn- install-method-closures
  [n method]
  (let [predicate-ids (vec (repeatedly (count (methods/spec-predicates method)) ids/new-node-id))
        matcher-id (ids/new-node-id)
        handler-id (ids/new-node-id)]
    {:net (-> n
              (#(reduce (fn [acc [id v]] (nb/install-cell acc id v v))
                        %
                        (map vector predicate-ids (methods/spec-predicates method))))
              (nb/install-cell matcher-id (methods/spec-matcher method) (methods/spec-matcher method))
              (nb/install-cell handler-id (methods/spec-handler method) (methods/spec-handler method)))
     :predicate-ids predicate-ids
     :matcher-id matcher-id
     :handler-id handler-id}))

(defn- install-method-branch
  [n method arg-ids result-bank-id]
  (let [{:keys [net predicate-ids matcher-id handler-id]} (install-method-closures n method)
        branch (dispatch/p:matched-handler-branch*
                (methods/spec-method-key method)
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
  [outer-net generic-value arg-ids]
  {:outer-net outer-net
   :generic-value generic-value
   :arg-ids arg-ids
   :default-present? (when-not (value/unusable? generic-value)
                       (methods/compound-slot-present? generic-value constants/default-slot))
   :default-value (when-not (value/unusable? generic-value)
                    (obj/slot-value generic-value constants/default-slot))
   :policy-present? (when-not (value/unusable? generic-value)
                      (methods/compound-slot-present? generic-value constants/policy-slot))
   :policy-value (when-not (value/unusable? generic-value)
                   (obj/slot-value generic-value constants/policy-slot))
   :methods (when-not (value/unusable? generic-value)
              (methods/generic-methods generic-value))})

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
       (= constants/select-one-policy-tag policy-value)))

(defn- generic-reducer-install
  [{:keys [methods result-bank-id default-id reduced-out-id]}]
  (dispatch/reduce-results
   (dispatch/select-one-policy (mapv methods/spec-method-key methods) default-id)
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
    (let [{generic-value :value materialized? :updated?}
          (materialize/materialize-generic-procedure outer-net generic-id)
          materialization-messages (if materialized?
                                     [(message generic-id generic-value)]
                                     [])
          context (make-application-context outer-net generic-value arg-ids)]
      (if-not (generic-application-ready? context)
        materialization-messages
        (let [{:keys [reduced-out-id] :as app} (build-generic-application context)
              after (application/run-reduced-application app)]
          (into materialization-messages
                (application/diff-reduced-output outer-net after reduced-out-id out-id)))))))

(defn p:apply-generic
  [generic-id arg-ids out-id]
  (prop/construct-propagator
   (generic-apply-activate generic-id (vec arg-ids) out-id)
   (into [generic-id] arg-ids)
   [generic-id out-id]))

(defn retained-apply-cache []
  (atom {:frames {}
         :stats {:retained/frame-builds 0
                 :retained/frame-runs 0}}))

(defmacro with-retained-apply-generic-values
  [& body]
  `(binding [*retained-apply-cache* (or *retained-apply-cache*
                                        (retained-apply-cache))]
     ~@body))

(defn retained-apply-stats []
  (when *retained-apply-cache*
    (:stats @*retained-apply-cache*)))

(defn- bump-retained-stat!
  [k]
  (when *retained-apply-cache*
    (swap! *retained-apply-cache* update-in [:stats k] (fnil inc 0))))

(defn- retained-frame-key
  [generic-value arity]
  [arity
   (obj/slot-value generic-value constants/policy-slot)
   (obj/slot-value generic-value constants/default-slot)
   (mapv (fn [method]
           [(methods/spec-method-key method)
            (methods/spec-predicates method)
            (methods/spec-matcher method)
            (methods/spec-handler method)])
         (methods/generic-methods generic-value))])

(defn- make-retained-generic-frame
  [generic-value arity]
  (let [generic-id (ids/new-node-id)
        arg-ids (vec (repeatedly arity ids/new-node-id))
        default-id (ids/new-node-id)
        n0 (reduce nb/install-cell net/empty-net
                   (into [generic-id default-id] arg-ids))
        n1 (nb/seed-cell n0 generic-id generic-value)
        n2 (nb/seed-cell n1 default-id
                         (obj/slot-value generic-value constants/default-slot))
        context (make-application-context n2 generic-value arg-ids)
        app (build-generic-application context)
        [reducer-prop-ids template]
        ((:reducer-install app) (:net app))]
    {:template template
     :generic-id generic-id
     :arg-ids arg-ids
     :reduced-out-id (:reduced-out-id app)
     :prop-ids (into (vec (:branch-prop-ids app)) reducer-prop-ids)
     :prop-state-cache (atom {})
     :prop-io-cache (atom {})}))

(defn- retained-frame
  [generic-value arity]
  (let [k (retained-frame-key generic-value arity)]
    (if-let [frame (get-in @*retained-apply-cache* [:frames k])]
      frame
      (let [frame (make-retained-generic-frame generic-value arity)]
        (bump-retained-stat! :retained/frame-builds)
        (swap! *retained-apply-cache* assoc-in [:frames k] frame)
        frame))))

(defn- apply-generic-value-retained
  [generic-value arg-values]
  (let [{:keys [template generic-id arg-ids reduced-out-id prop-ids
                prop-state-cache prop-io-cache]}
        (retained-frame generic-value (count arg-values))
        n0 (nb/seed-cell template generic-id generic-value)
        n1 (reduce (fn [acc [id v]]
                     (nb/seed-cell acc id v))
                   n0
                   (map vector arg-ids arg-values))
        n2 (runner-tasks/run-props-with-state-cache n1
                                                    prop-ids
                                                    prop-state-cache
                                                    prop-io-cache)]
    (bump-retained-stat! :retained/frame-runs)
    (net/network-cell-strongest n2 reduced-out-id)))

(defn apply-generic-value
  "Apply an already-realized generic procedure value to plain argument values.

  This runs the generic application in a temporary network and returns the
  selected output value. The temporary network has no cell-protocol dict keys, so
  merge/strongest protocol hooks do not recursively dispatch while this helper
  is evaluating a protocol generic."
  [generic-value arg-values]
  (if *retained-apply-cache*
    (cache/cached
     [:generic-procedure/apply-generic-value-retained
      (retained-frame-key generic-value (count arg-values))
      arg-values]
     #(apply-generic-value-retained generic-value arg-values))
    (cache/cached
     [:generic-procedure/apply-generic-value generic-value arg-values]
     #(do
        (cache/stat! :generic-procedure/apply-generic-value)
        (let [generic-id (ids/new-node-id)
              arg-ids (vec (repeatedly (count arg-values) ids/new-node-id))
              out-id (ids/new-node-id)
              n0 (reduce nb/install-cell net/empty-net
                         (into [generic-id out-id] arg-ids))
              n1 (nb/seed-cell n0 generic-id generic-value)
              n2 (reduce (fn [acc [id v]] (nb/seed-cell acc id v))
                         n1
                         (map vector arg-ids arg-values))
              [apply-prop n3] ((p:apply-generic generic-id arg-ids out-id) n2)
              n4 (nb/run-propagators n3 [apply-prop])]
          (net/network-cell-strongest n4 out-id))))))

(defn p:generic-operator
  [generic-id]
  (fn [& node-ids]
    (let [nodes (vec node-ids)]
      (p:apply-generic generic-id (vec (butlast nodes)) (last nodes)))))
