(ns propagators.compiler-2.runtime.linked-application
  "Apply callables to live argument-cell lists without closing open tails."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.declaration-effects :as effects]
            [propagators.compiler-2.runtime.list-interface :as list-interface]
            [propagators.compiler-2.runtime.rest-application :as rest-application]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.membership-collection :as membership-collection]
            [propagators.stdlib.premise-state :as premise-state]
            [propagators.datastructures.support-collection :as support-collection]
            [propagators.datastructures.support :as support]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]))

(declare apply-list-effects)

(defn- base [candidate]
  (if (datum/layer-present? candidate :base)
    (datum/layer-value candidate :base)
    candidate))

(defn- matching-callable [candidate required-count rest?]
  (cond
    (value/unusable? candidate) candidate
    (and (= required-count (rest-application/required-argument-count candidate))
         (= rest? (rest-application/rest-callable? candidate)))
    (rest-application/linked-callable candidate)
    :else value/nothing))

(defn- callable-content [network operator-id owners completion-id required-count rest?]
  (let [candidate (net/network-cell-strongest network operator-id)
        sources (into [candidate]
                      (map #(net/network-cell-strongest network %)
                           (cond-> owners (some? completion-id) (conj completion-id))))
        contradiction (some (fn [source]
                              (when (value/contradiction? (base source)) (base source))) sources)
        available? (and (every? #(= :cons (list-interface/list-shape network %)) owners)
                        (or (nil? completion-id)
                            (= :empty (list-interface/list-shape network completion-id))))
        selected (cond
                   contradiction contradiction
                   available? (matching-callable (base candidate) required-count rest?)
                   :else value/nothing)
        supported? (some #(and (datum/layer-present? % :base)
                               (datum/layer-present? % :support)) sources)]
    (if supported?
      (let [content {:base selected
                     :support (reduce support/combine #{} (map datum/support-of sources))
                     :premise-state (reduce support/join #{} (map premise-state/states-of sources))}]
        (if (some #(datum/layer-present? % :membership) sources)
          (membership-collection/content
           (assoc content :membership
                  (reduce support/combine #{} (map datum/membership-of sources))))
          (support-collection/content content)))
      selected)))

(defn- invocation-effects
  [operator-id context-id prefix result-id required-count rest? owners completion-id]
  (let [key [::callable operator-id required-count rest? owners completion-id]
        adapted-id (gur/stable-node-id key)
        adapter-id (gur/stable-node-id [key :adapt])]
    [(gur/declare-cell adapted-id)
     (gur/declare-prop
      adapter-id (cond-> (into [operator-id] owners)
                   (some? completion-id) (conj completion-id)) [adapted-id]
      (fn [_ _ network]
        (let [content (callable-content network operator-id owners completion-id required-count rest?)]
          (if (value/nothing? content)
            []
            [(message adapted-id content)]))))
     (gur/apply-closure-effect adapted-id (into [context-id] prefix) result-id)]))

(defn- invalid-tail! [list-id]
  (throw (ex-info "apply requires a topology-backed linked list" {:list-id list-id})))

(defn- list-identity [network list-id]
  (let [current (base (net/network-cell-strongest network list-id))]
    (if (obj/accessor-network? current)
      (let [heads (obj/accessor-parent-ids current :car)
            tails (obj/accessor-parent-ids current :cdr)]
        (if (or (seq heads) (seq tails))
          [::owner heads tails]
          list-id))
      list-id)))

(defn- next-list-effects [network operator-id context-id prefix seen owners list-id result-id]
  (let [key [::slots operator-id context-id prefix list-id result-id]
        [head head-props with-head]
        (obj/install-slot-access (effects/begin network) :car list-id
                                 (gur/stable-node-id [key :head]))
        [tail tail-props with-tail]
        (obj/install-slot-access with-head :cdr list-id
                                 (gur/stable-node-id [key :tail]))
        declared (effects/result
                  (effects/register-props with-tail (concat head-props tail-props)))]
    {:effects (into (:effects declared)
                    (apply-list-effects operator-id context-id
                                        (conj prefix head) tail result-id
                                        (conj seen (list-identity network list-id))
                                        (conj owners list-id)))
     :messages (:messages declared)}))

(defn- apply-list-body [operator-id context-id prefix seen owners]
  (fn [context [_ list-id] result-id]
    (let [network (:network context)
          candidate (base (net/network-cell-strongest network operator-id))
          required-count (rest-application/required-argument-count candidate)
          rest? (rest-application/rest-callable? candidate)
          shape (list-interface/list-shape network list-id)]
      (when (contains? seen (list-identity network list-id))
        (throw (ex-info "Cyclic input interfaces are unsupported" {:list-id list-id})))
      (cond
        (and (some? required-count) (> (count prefix) required-count))
        {:effects []}

        (and (some? required-count) (= (count prefix) required-count))
        (cond
          (= :scalar shape) (invalid-tail! list-id)
          rest?
          {:effects (invocation-effects operator-id context-id (conj prefix list-id)
                                         result-id required-count true owners nil)}
          (= :cons shape)
          (throw (ex-info "Closure application has invalid arity"
                          {:required-count required-count :argument-count (inc (count prefix))}))
          :else
          {:effects (invocation-effects operator-id context-id prefix result-id
                                         required-count false owners nil)})

        :else
        (case shape
          :empty
          {:effects (invocation-effects operator-id context-id prefix result-id
                                         required-count false owners
                                         (if (nil? required-count) list-id nil))}
          :cons
          (next-list-effects network operator-id context-id prefix seen owners list-id result-id)
          :wait {:effects []}
          :scalar (invalid-tail! list-id))))))

(defn- apply-list-effects
  ([operator-id context-id prefix list-id result-id]
   (apply-list-effects operator-id context-id prefix list-id result-id #{} []))
  ([operator-id context-id prefix list-id result-id seen owners]
   (let [key [:compiler-2/apply-list operator-id context-id prefix list-id result-id owners]
         activate (apply-list-body operator-id context-id prefix seen owners)]
     [(gur/declare-prop
       (gur/stable-node-id [key :scan]) [operator-id list-id] [result-id]
       (fn [_ _ network]
         (activate {:network network} [operator-id list-id] result-id)))])))

(def apply-operator
  (operator/operator-closure
   {:name 'apply
    :compiler-activate
    (fn [_ network context-id arguments result-id]
      (if (= 2 (count arguments))
        {:effects (apply-list-effects (first arguments) context-id []
                                      (second arguments) result-id)}
        (throw (ex-info "apply expects a network and an argument-cell list"
                        {:arguments arguments}))))}))

(defn linked-list-application-effects [operator-id context-id list-id result-id]
  (apply-list-effects operator-id context-id [] list-id result-id))
