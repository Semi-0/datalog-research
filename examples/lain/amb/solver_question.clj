(ns examples.lain.amb.solver-question
  "Instantiate one supplied Lain callable; all strategies consume this question."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.experimental.amb.primitives :as amb]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.supported-procedure :as supported]))

(defn build [network callable sources domain]
  (if (and (= 3 (count sources)) (= 3 (count (distinct sources)))
           (every? #(value/nothing? (net/network-cell-strongest network %)) sources)
           (application/compiler-callable? (net/network-cell-strongest network callable)))
    (let [[context raw-valid valid failure tuple output] (repeatedly 6 ids/new-node-id)
          prepared (-> (reduce nb/ensure-cell network [context raw-valid valid failure tuple output])
                       (nb/seed-cell context :constraint-query))
          [applied _] (application/install-application
                       {:net prepared :props [] :context-id context
                        :application/app-id [::query sources]}
                       callable sources raw-valid)
          ready (supported/from-primitive
                 (prop/primitive-propagator
                  ::complete (fn [& values]
                               (if (apply value/any-unusable-values? values)
                                 value/nothing (last values)))))
          [ready-net ready-tasks _] (supported/install ready (:net applied) (conj sources raw-valid) valid)
          base (supported/from-primitive (prop/primitive-propagator ::tuple vector))
          [tuple-net tuple-tasks _] (supported/install base ready-net sources tuple)
          [require-id required] ((amb/p:require valid failure) tuple-net)
          switch (get (into {} (extension/extension-bindings ttms/session-extension)) 'switch)
          [installed switch-tasks _] ((operator/operator-install switch) required [tuple valid] output)]
      {:network installed :sources sources :domain domain
       :tasks (tq/into-queue (concat (:props applied) ready-tasks tuple-tasks [require-id] switch-tasks))
       :cells {:valid valid :failure failure :tuple tuple :output output}
       :callable callable})
    (throw (ex-info "Solver expects a Lain callable and three distinct empty source cells"
                    {:callable callable :sources sources}))))
