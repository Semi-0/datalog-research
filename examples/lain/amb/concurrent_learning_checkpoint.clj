(ns examples.lain.amb.concurrent-learning-checkpoint
  "Partition-local learning composed with the existing immutable worker driver.
  No mutable shared knowledge; every worker keeps its no-goods across restores."
  (:require [examples.lain.amb.checkpoint-enumeration :as enumeration]
            [examples.lain.amb.learning-checkpoint :as hybrid]
            [examples.lain.amb.parallel-benchmark :as profiling]
            [examples.lain.amb.solver-strategy :as strategy]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.amb.observation :as observation]))

(defn evaluate [built state]
  (let [result (enumeration/evaluate built state)]
    (assoc result :nogoods
           (observation/cell-base (:network result) (get-in built [:cells :knowledge])))))

(defn profile-worker [built state]
  (profiling/profile #(evaluate built state)))

(defn run [question config]
  (let [built (enumeration/extend-all-answers (hybrid/build question {:learn? true}))]
    (enumeration/run-partitions {:question question :built built}
                                (:workers config) (:executor config) profile-worker)))

(defrecord PartitionedLearningSolver [concurrent?]
  strategy/ConstraintSolver
  (solve-question [_ question context]
    (if (and concurrent? (nil? strategy/*executor*))
      (throw (ex-info "Concurrent learning requires a runtime executor" {}))
      (run question {:workers (:workers context)
                     :executor (if concurrent? strategy/*executor* nil)}))))

(def sequential-solver (->PartitionedLearningSolver false))
(def concurrent-solver (->PartitionedLearningSolver true))

(def session-extension
  (extension/extension-bundle
   {:id ::solvers
    :bindings [['partitioned-learning-solver sequential-solver]
               ['concurrent-learning-solver concurrent-solver]]
    :effects []}))
