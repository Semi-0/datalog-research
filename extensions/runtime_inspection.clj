(ns extensions.runtime-inspection
  "Loadable retraction-inspection propagators for compiler-2 runtime blocks."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.operators.block-premise :as premise]
            [propagators.compiler-2.runtime.boundary :as boundary]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.inspection.retraction :as retraction]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- profile-request-messages
  [network instance-id index-id target-id]
  (let [instance (net/network-cell-strongest network instance-id)
        block-index (net/network-cell-strongest network index-id)]
    (if (or (value/unusable? instance) (value/unusable? block-index))
      []
      (do
        (when-not (integer? block-index)
          (throw (ex-info "inspect:profile-next-commit expects an integer block index"
                          {:block-index block-index})))
        (let [resolved-instance
              (cond
                (ids/node-id? instance) instance
                (net/net? instance)
                (some-> (obj/accessor-source-slots instance) :uuid ids/->NodeId)
                :else nil)
              _ (when-not resolved-instance
                  (throw (ex-info "inspection target is not a runtime instance"
                                  {:value instance})))
              effect-id [:inspection/profile-next-commit
                         target-id resolved-instance block-index]
              request (boundary/inspection-profile-request
                       effect-id resolved-instance block-index target-id
                       (premise/binding-contexts network target-id))]
          [(message
            (runtime-ids/boundary-outbox-id)
            (obj/compound-object
             {(runtime-ids/effect-slot-key effect-id) request}))])))))

(defn- install-profile-next-commit
  [network arg-ids fallback-id]
  (let [[instance-id index-id maybe-target] (vec arg-ids)
        target-id (cond
                    (some? maybe-target) maybe-target
                    :else fallback-id)]
    (when-not (#{2 3} (count arg-ids))
      (throw (ex-info
              "inspect:profile-next-commit expects instance, block index, and optional output"
              {:arg-ids arg-ids})))
    (let [outbox-id (runtime-ids/boundary-outbox-id)
          prepared (-> network
                       (nb/ensure-cell target-id)
                       (nb/ensure-cell outbox-id))
          [prop-id installed]
          ((prop/construct-propagator
            (runtime-ids/stable-node-id :inspection/profile-next-commit
                                        instance-id index-id target-id)
            :inspection/profile-next-commit
            (fn [_inputs _outputs current]
              (profile-request-messages current instance-id index-id target-id))
            [instance-id index-id]
            [outbox-id])
           prepared)]
      [installed [prop-id] target-id])))

(defn profile-next-commit-operator
  []
  (operator-value/operator-closure
   {:name 'inspect:profile-next-commit
    :install install-profile-next-commit
    :boundary-output-cell-ids
    (operator-value/fixed-boundary-cell-ids
     (runtime-ids/boundary-outbox-id))
    :boundary-dict-keys operator-value/effect-boundary-dict-keys
    :output-selector (fn [arg-ids fallback-id]
                       (let [target-id (nth (vec arg-ids) 2 nil)]
                         (cond
                           (some? target-id) target-id
                           :else fallback-id)))}))

(defn classify-retraction
  [report]
  (retraction/classify-report report))

(defn report-summary
  [report]
  (retraction/report-summary report))

(defn primitive-bindings
  []
  [['inspect:profile-next-commit (profile-next-commit-operator)]
   ['inspect:classify-retraction
    (basis/primitive-operator classify-retraction)]
   ['inspect:summary (basis/primitive-operator report-summary)]])
