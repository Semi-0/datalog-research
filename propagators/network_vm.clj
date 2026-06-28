(ns propagators.network-vm
  "Experimental monotone network VM facade.

  This namespace is intentionally separate from `propagators.gur`; it is a
  parallel experiment, not the canonical GUR implementation."
  (:require [propagators.network-vm.executor :as executor]
            [propagators.network-vm.instructions :as instructions]))

(def task-index-key instructions/task-index-key)
(def name-bindings-key instructions/name-bindings-key)

(def declare-cell instructions/declare-cell)
(def declare-prop instructions/declare-prop)
(def bind-name instructions/bind-name)
(def tell instructions/tell)
(def schedule instructions/schedule)

(def state executor/state)
(def apply-instruction executor/apply-instruction)
(def apply-instructions executor/apply-instructions)
(def advance executor/advance)
(def temperature executor/temperature)
(def cold? executor/cold?)
(def run-until-cold executor/run-until-cold)
(def pending-task-count executor/pending-task-count)
