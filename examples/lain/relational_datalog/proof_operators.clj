(ns examples.lain.relational-datalog.proof-operators
  "Persistent candidate networks; ordinary TTMS cells own unification conflicts."
  (:require [examples.lain.relational-datalog.proof-relation :as relation]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.layered.procedure :as layer]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.premise-state :as state]
            [propagators.stdlib.support :as supported]))

(def left-middle
  (ttms/scalar-operator ::left-middle
                       (prop/concrete-primitive-propagator ::left-middle #(nth % 1))))

(def right-middle
  (ttms/scalar-operator ::right-middle
                       (prop/concrete-primitive-propagator ::right-middle #(nth % 0))))

(defn- candidate-base [[left right binding]]
  ;; A concrete binding alone is insufficient: both assignments must have
  ;; reached it, at compatible source versions, before a tuple is justified.
  (let [bases (mapv #(datum/layer-value % :base) [left right binding])
        required (support/combine (datum/support-of left) (datum/support-of right))
        bound (datum/support-of binding)]
    (cond
      (some value/nothing? (take 2 bases)) value/nothing
      (not (support/covers? bound required)) value/nothing
      (some value/contradiction? bases) value/contradiction
      (or (some value/unusable? [left right binding])
          (not (support/compatible? required bound))) value/nothing
      :else [(nth (first bases) 0) (nth (second bases) 1)])))

(defn- install-endpoints [network arguments output]
  (let [[procedure base-id support-id state-id] (repeatedly 4 ids/new-node-id)
        base (layer/argument-layer :base (constantly true) candidate-base)
        declarations [[:base base-id base] [:support support-id supported/procedure]
                      [:premise-state state-id state/procedure]]
        prepared (reduce nb/ensure-cell network (conj (vec arguments) output))
        installed
        (reduce (fn [current [tag id definition]]
                  (:net (layered/install-layered-procedure!
                         (nb/install-cell current id definition definition)
                         procedure tag id)))
                prepared declarations)
        [id applied] ((layered/p:apply-layered procedure arguments output) installed)
        application (net/network-env-lookup applied id)
        activation (prop/compose-activation
                    (prop/prop-f application)
                    ;; Keep contradictory base evidence. Unlike a concrete
                    ;; consumer, this publication boundary transports it.
                    (message/lift-message collection/content))]
    [(net/assoc-net-prop applied id
                         (assoc application :activate activation
                                :name [::candidate (last arguments)]))
     [id] output]))

(def endpoints
  (operator/operator-closure {:name ::endpoints :install install-endpoints}))

(defn- install-candidate [network arguments output]
  (if (= 2 (count arguments))
    (let [[left right] arguments
          binding (ids/new-node-id)
          bottom (collection/content {:premise-state #{}})
          prepared (nb/install-cell network binding bottom value/nothing)
          declarations [[left-middle [left] binding]
                        [right-middle [right] binding]
                        [endpoints [left right binding] output]]
          [installed tasks]
          (reduce
           (fn [[current tasks] [procedure inputs target]]
             (let [[next-network more _]
                   ((operator/operator-install procedure) current inputs target)]
               [next-network (into tasks more)]))
           [prepared []] declarations)]
      [installed tasks output])
    (throw (ex-info "unify-path expects two edge cells" {:arguments arguments}))))

(def unify-path
  (operator/operator-closure {:name ::unify-path :install install-candidate}))

(def proof-relation
  (operator/propagator-operator
   {:name ::proof-relation
    :messages
    (fn [network {:keys [inputs outputs]}]
      [(message/message
        (first outputs)
        (relation/relation
         (into {} (map (fn [id] [id (net/network-cell-content network id)])) inputs)))])}))

(def session-extension
  (extension/extension-bundle
   {:id ::proofs :bindings [['unify-path unify-path] ['proof-relation proof-relation]]
    :effects []}))

(defn bindings-in [network]
  (into {}
        (keep (fn [[_id entry]]
                (let [name (:name entry)]
                  (if (and (vector? name) (= ::candidate (first name)))
                    (let [id (second name)]
                      [id (relation/proof-state (net/network-cell-strongest network id))])
                    nil))))
        (net/net-env network)))
