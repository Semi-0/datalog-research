(ns propagators.experimental.amb.operators
  "Opt-in bindings over builder primitives; no compiler handlers or effects."
  (:require [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.amb.primitives :as primitives]))

(defn- arguments [name expected ids]
  (if (= expected (count ids))
    (vec ids)
    (throw (ex-info "Wrong AMB operator arity"
                    {:operator name :expected expected :arguments ids}))))

(defn- installer [name arity constructor]
  (fn [network ids output]
    (let [ids (arguments name arity ids)
          [id installed] ((apply constructor (conj ids output)) network)]
      [(declarations/register-props installed [id]) [id] output])))

(defn- ordinary-operator [name arity constructor]
  (operator/operator-closure
   {:name [::operator name] :install (installer name arity constructor)}))

(def amb-operator
  (operator/operator-closure
   {:name [::operator 'amb]
    :input-selector (fn [ids _ _] (subvec (arguments 'amb 3 ids) 0 2))
    :output-selector (fn [ids output] [output (nth (arguments 'amb 3 ids) 2)])
    :install (installer 'amb 3 primitives/p:amb)}))

(def session-extension
  (extension/extension-bundle
   {:id ::search
    :bindings [['amb amb-operator]
               ['require (ordinary-operator 'require 1 primitives/p:require)]
               ['learn (ordinary-operator 'learn 2 primitives/p:learn)]
               ['backtrack (ordinary-operator 'backtrack 2 primitives/p:backtrack)]]
    :effects []}))
