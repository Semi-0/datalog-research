(ns examples.lain.amb.compiler-checkpoint
  "Matched compiled and builder questions; the checkpoint solver is unchanged."
  (:require [examples.lain.amb.builder :as builder]
            [examples.lain.amb.checkpoint-question :as question]
            [propagators.cells.value :as value]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.experimental.amb.primitives :as amb]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def body
  "(let [] (define valid (predicate a b c))
     (require valid) (switch (tuple a b c) valid))")

(def sources
  {:direct body
   :application (str "((network (a b c) " body ") a b c)")
   :arithmetic
   "((network (a b c)
       (let []
         (define valid (and (<= a b) (<= b c)
                            (= (+ (* a a) (* b b)) (* c c))))
         (require valid) (switch (tuple a b c) valid))) a b c)"})

(defn- calculation [name f]
  (ttms/scalar-operator
   name (prop/primitive-propagator
         name (fn [& values]
                (if (apply value/any-unusable-values? values)
                  value/nothing
                  (apply f values))))))

(defn- require-at [failure]
  ;; Use the existing constructor, but expose its certificate to the solver.
  (operator/operator-closure
   {:name ::require
    :output-selector (fn [_ _] [failure])
    :install (fn [network arguments _]
               (if (= 1 (count arguments))
                 (let [[id installed] ((amb/p:require (first arguments) failure) network)]
                   [(declarations/register-props installed [id]) [id] failure])
                 (throw (ex-info "Require expects one input" {:arguments arguments}))))}))

(defn build
  ([n shape] (build n shape builder/pythagorean?))
  ([n shape predicate]
   (if (and (integer? n) (pos? n) (contains? sources shape))
     (let [[a b c failure] (repeatedly 4 ids/new-node-id)
           inputs [a b c]
           bindings (into [['predicate (calculation ::question/constraint predicate)]
                           ['tuple (calculation ::question/tuple vector)]
                           ['require (require-at failure)]]
                          (map (fn [name id] [name (env/cell-binding id)]) '[a b c] inputs))
           session (-> (state/empty-state)
                       (update :program/net #(reduce nb/ensure-cell % (conj inputs failure)))
                       (extension/install-session-extension ttms/session-extension {})
                       (extension/install-session-extension
                        (extension/extension-bundle
                         {:id ::question :bindings bindings :effects []}) {}))
           compiled (compiler/compile-source
                     (get sources shape) (:program/env session)
                     {:net (:program/net session) :environment-props (:program/props session)})]
       {:network (:net compiled) :tasks (:props compiled)
        :sources inputs :domain (vec (range 1 (inc n)))
        :cells {:failure failure :output (:cell compiled)}
        :compiled compiled :source (get sources shape) :shape shape})
     (throw (ex-info "Expected positive bound and known compiled shape"
                     {:bound n :shape shape})))))
