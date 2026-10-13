(ns examples.lain.amb.solver-strategy
  "Strategy selection only. Thread pools remain runtime-owned, outside Net."
  (:require [examples.lain.amb.checkpoint-enumeration :as checkpoint]
            [examples.lain.amb.parallel-benchmark :as profiling]
            [examples.lain.amb.repair-enumeration :as repair]))

(def ^:dynamic *executor* nil)

(defn profile-worker [built state]
  (profiling/profile #(checkpoint/evaluate built state)))

(defprotocol ConstraintSolver
  (solve-question [solver question context]))

(defrecord RepairSolver []
  ConstraintSolver
  (solve-question [_ question _]
    (repair/run (repair/extend-question question))))

(defrecord CheckpointSolver []
  ConstraintSolver
  (solve-question [_ question _]
    (checkpoint/run (checkpoint/template question))))

(defrecord PartitionedSolver []
  ConstraintSolver
  (solve-question [_ question context]
    (checkpoint/run-partitions (checkpoint/template question) (:workers context) nil profile-worker)))

(defrecord ConcurrentSolver []
  ConstraintSolver
  (solve-question [_ question context]
    (if (some? *executor*)
      (checkpoint/run-partitions (checkpoint/template question) (:workers context) *executor* profile-worker)
      (throw (ex-info "Concurrent solver needs a runtime executor" {})))))

(def strategies
  {:repair (->RepairSolver) :checkpoint (->CheckpointSolver)
   :partitioned-sequential (->PartitionedSolver) :concurrent (->ConcurrentSolver)})

(defn select [key]
  (if (contains? strategies key)
    (get strategies key)
    (throw (ex-info "Unknown solver strategy" {:strategy key}))))
