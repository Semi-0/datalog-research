(ns propagators.experimental.visualization.layered-primitives
  "Opt-in primitive composition; no compiler or layered-runtime changes."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.scope-source :as scope]
            [propagators.datastructures.tms.core :as tms]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.layered.procedure :as layer-procedure]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.arithmetic.base :as base]
            [propagators.stdlib.prop :as standard]))

(defn- input-value
  "Expose the arithmetic base and dependency layer, without source write-back."
  [v]
  (let [unwrapped (tms/distributed-base-value (scope/unwrap v))]
    (cond
      (value/unusable? unwrapped) unwrapped
      :else
      (dependency/dependency-value
       (dependency/unwrap unwrapped)
       (set/union (dependency/sources unwrapped)
                  (if (scope/scope-value? v) (scope/dependencies v) #{}))))))

(def sources-closure
  (layer-procedure/argument-layer
   dependency/sources-layer
   (fn [arguments]
     (boolean (some #(datum/layer-present? % dependency/sources-layer) arguments)))
   (fn [arguments] (apply set/union #{} (map dependency/sources arguments)))))

(defn- install-procedure
  [network base-closure]
  (let [base-closure (layer-procedure/base base-closure)
        [procedure base-id sources-id] (repeatedly 3 ids/new-node-id)
        prepared (-> network
                     (nb/install-cell base-id base-closure base-closure)
                     (nb/install-cell sources-id sources-closure sources-closure))
        base-layer (layered/install-layered-procedure!
                    prepared procedure :base base-id)
        sources-layer (layered/install-layered-procedure!
                       (:net base-layer) procedure dependency/sources-layer sources-id)]
    {:net (:net sources-layer) :procedure procedure}))

(defn- output-messages
  [network arguments layered-result output]
  ;; Readiness is owned by the enclosing concrete-propagator, not this adapter.
  (let [result (net/network-cell-strongest network layered-result)
        contents (mapv #(net/network-cell-content network %) arguments)
        payload (dependency/dependency-value
                 (dependency/unwrap result) (dependency/sources result))
        update (tms/distributed-result-update [::result output] payload contents)]
    [(message output (if (some? update) update payload))]))

(defn- concrete-layered-call
  "Scalar observations wait for arguments; generic layered procedures need not."
  [procedure arguments result]
  (fn [network]
    (let [[id installed]
          ((layered/p:apply-layered ::apply procedure arguments result) network)
          application (net/network-env-lookup installed id)
          activate (prop/concrete-propagator (prop/prop-f application))]
      [id (net/assoc-net-prop
           installed id
           (assoc application :activate
                  (fn [_inputs _outputs current]
                    (activate arguments [result] current))))])))

(defn install-call
  "Declare input adapters -> layered application -> support-preserving output.
  Argument adapters are one-way. All persistent state lives in ordinary cells."
  [network arguments output base-closure]
  (let [arguments (vec arguments)]
    (when-not (= 2 (count arguments))
      (throw (ex-info "Tracked arithmetic expects two arguments"
                      {:arguments arguments})))
    (let [{:keys [net procedure]} (install-procedure network base-closure)
          adapted (vec (repeatedly 2 ids/new-node-id))
          result (ids/new-node-id)
          prepared (reduce nb/ensure-cell net (concat arguments adapted [output result]))
          installers (concat
                      (map (fn [source target]
                             ((prop/primitive-propagator ::input input-value) source target))
                           arguments adapted)
                      [(concrete-layered-call procedure adapted result)
                       (prop/construct-propagator
                        ::output
                        (prop/concrete-propagator
                         (fn [_inputs _outputs current]
                           (output-messages current arguments result output)))
                        (conj arguments result) [output])])
          [installed props]
          (reduce (fn [[current props] installer]
                    (let [[id next] (installer current)]
                      [next (conj props id)]))
                  [prepared []] installers)]
      [installed props output])))

(def tracked-plus
  (operator/operator-closure
   {:name ::tracked-plus
    :install (fn [network arguments output]
               (install-call network arguments output base/plus-closure))}))

(def tracked-or
  (operator/operator-closure
   {:name ::tracked-or
    :install (fn [network arguments output]
               (install-call network arguments output
                             (base/arithmetic-base-closure standard/or)))}))

(def session-extension
  (extension/extension-bundle
   {:id ::primitives
    :bindings [['tracked+ tracked-plus] ['tracked-or tracked-or]]
    :effects []}))

(defn- ttms-operator [name base-closure]
  (ttms/procedure-operator
   name
   {:net net/empty-net
    :f (fn [_ inputs outputs network]
         (first (install-call network inputs (first outputs) base-closure)))}))

(def ttms-extension
  (extension/extension-bundle
   {:id ::ttms-primitives
    :bindings (let [plus (ttms-operator ::ttms-plus base/plus-closure)
                    either (ttms-operator ::ttms-or (base/arithmetic-base-closure standard/or))]
                [['+ plus] ['tracked+ plus] ['or either] ['tracked-or either]])
    :effects []}))
