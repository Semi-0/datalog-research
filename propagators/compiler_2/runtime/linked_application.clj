(ns propagators.compiler-2.runtime.linked-application
  "Apply an ordinary callable to a finite live argument-cell list."
  (:require [propagators.compiler-2.runtime.list-interface :as list-interface]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]))

(declare apply-list-effects)

(defn- apply-list-body [operator-id context-id prefix seen]
  (fn [context [list-id] result-id]
    (let [network (:network context)
          head (obj/existing-slot-cell-id network :car list-id)
          tail (obj/existing-slot-cell-id network :cdr list-id)]
      (when (contains? seen list-id)
        (throw (ex-info "Cyclic input interfaces are unsupported" {:list-id list-id})))
      (case (list-interface/list-shape network list-id)
        :empty
        {:effects [(gur/apply-closure-effect operator-id
                                            (into [context-id] prefix)
                                            result-id)]}
        :cons
        {:effects (apply-list-effects operator-id context-id
                                      (conj prefix head) tail result-id
                                      (conj seen list-id))}
        :wait {:effects []}
        :scalar
        (throw (ex-info "apply requires a topology-backed linked list"
                        {:list-id list-id}))))))

(defn- apply-list-effects
  ([operator-id context-id prefix list-id result-id]
   (apply-list-effects operator-id context-id prefix list-id result-id #{}))
  ([operator-id context-id prefix list-id result-id seen]
   (let [key [:compiler-2/apply-list operator-id context-id prefix list-id result-id]
         closure-id (gur/stable-node-id [key :closure])
         condition-id (gur/stable-node-id [key :ready])]
     (into (list-interface/list-readiness-effects key list-id condition-id)
           [(gur/declare-cell closure-id)
            (gur/when-effect
             key condition-id
             (fn []
               {:messages [(message closure-id
                                    (gur/recursive-closure
                                     key (apply-list-body operator-id context-id prefix seen)))]
                :effects [(gur/apply-closure-effect closure-id [list-id] result-id)]}))]))))

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
