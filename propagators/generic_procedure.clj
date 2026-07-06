(ns propagators.generic-procedure
  "Propagator-native generic procedures.

  A generic procedure cell is a named-network value. Initialization installs the
  reducer policy/default slots once; method definitions merge compiled branch
  slots into the same cell."
  (:require [propagators.generic-procedure.application :as application]
            [propagators.generic-procedure.define :as define]
            [propagators.generic-procedure.match :as match]
            [propagators.generic-procedure.materialize :as materialize]))

(def predicate-closure match/predicate-closure)
(def match-args-closure match/match-args-closure)
(def all-args-match-closure match/all-args-match-closure)
(def handler-closure match/handler-closure)
(def match-cells match/match-cells)
(def match-cells-pred match/match-cells-pred)

(def make-generic-propagator define/make-generic-propagator)
(def define-generic-propagator define/define-generic-propagator)
(def define-generic-propagator-handler define/define-generic-propagator-handler)

(def materialize-generic-procedure materialize/materialize-generic-procedure)

(def p:apply-generic application/p:apply-generic)
(def apply-generic-value application/apply-generic-value)
(def retained-apply-cache application/retained-apply-cache)
(def retained-apply-stats application/retained-apply-stats)

(defmacro with-retained-apply-generic-values
  [& body]
  `(application/with-retained-apply-generic-values ~@body))
(def p:generic-operator application/p:generic-operator)
