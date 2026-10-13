(ns examples.lain.amb.solver-operator
  "One opt-in solve call. Same missing-cell contract for every strategy."
  (:require [examples.lain.amb.solver-question :as question]
            [examples.lain.amb.solver-strategy :as strategy]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.publication :as publication]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def ^:dynamic *observe-solve* (fn [_] nil))

(defn arguments [ids]
  (if (= 6 (count ids))
    (vec ids)
    (throw (ex-info "solve expects solver, constraint, domain, and three missing cells"
                    {:arguments ids}))))

(defn- prepare-question [call query]
  (prop/concrete-propagator
   (fn [_ _ network]
     (let [[_ callable domain & sources] (:arguments call)
           snapshot (observation/list-snapshot network domain)
           previous (observation/cell-base network query)
           descriptor {:callable (observation/cell-base network callable)
                       :domain (:items snapshot) :sources (vec sources)}]
       (cond
         (not (:ready? snapshot)) []
         (not (value/nothing? previous))
         (if (= descriptor (:descriptor previous)) []
             (throw (ex-info "Fixed enumeration query changed; rebuild its environment" {})))
         :else
         (let [domain (:items snapshot)
               _ (if (and (seq domain) (= (count domain) (count (distinct domain))))
                   nil (throw (ex-info "Solver needs a distinct nonempty finite domain" {:domain domain})))
               built (question/build network callable (vec sources) domain)]
           [(publication/snapshot-message network query {:descriptor descriptor :question built})]))))))

(defn- execute-question [context call controls]
  (prop/concrete-propagator
   (fn [_ _ network]
     (let [selected (observation/cell-base network (first (:arguments call)))
           built (:question (observation/cell-base network (:query controls)))
           previous (observation/cell-base network (:receipt controls))]
       (cond
         (not (satisfies? strategy/ConstraintSolver selected))
         (throw (ex-info "Selected value does not implement ConstraintSolver" {}))
         (= selected (:solver previous)) []
         :else
         (let [solve (partial strategy/solve-question selected built)
               result (solve context)]
           (if (= :completed (:status result))
             (do (*observe-solve* {:question built :result result :solver selected :context context})
                 [(publication/snapshot-message network (:output call) (:answers result))
                  (publication/snapshot-message network (:receipt controls)
                                                {:solver selected :status :completed})])
             (throw (ex-info "Solver did not complete its enumeration" {:status (:status result)})))))))))

(defn solve-binding [config]
  (if (and (integer? (:workers config)) (pos? (:workers config)))
    (operator/operator-closure
     {:name ::solve
      :input-selector (fn [ids _ _] (subvec (arguments ids) 0 3))
      :install (fn [network ids output]
                 (let [ids (arguments ids) [query receipt] (repeatedly 2 ids/new-node-id)
                       call {:arguments ids :output output} controls {:query query :receipt receipt}
                       prepared (reduce nb/ensure-cell network (conj ids output query receipt))
                       [prepare-id prepared] ((prop/construct-propagator
                                               ::prepare (publication/lift (prepare-question call query))
                                               (subvec ids 1 3) [query]) prepared)
                       [execute-id installed] ((prop/construct-propagator
                                                ::solve (publication/lift (execute-question config call controls))
                                                [(first ids) query] [output receipt]) prepared)
                       props [prepare-id execute-id]]
                   [(declarations/register-props installed props) props output]))})
    (throw (ex-info "Invalid finite solver configuration" {:config config}))))

(defn session-extension [config]
  (extension/extension-bundle
   {:id ::solver :bindings [['solve (solve-binding config)]
                           ['repair-solver (strategy/select :repair)]
                           ['checkpoint-solver (strategy/select :checkpoint)]
                           ['concurrent-solver (strategy/select :concurrent)]] :effects []}))
