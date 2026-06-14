(ns propagators.generic-procedure.match
  (:require [propagators.closure :as closure]))

(def predicate-closure closure/primitive-closure)
(def match-args-closure closure/primitive-closure)
(def all-args-match-closure
  (match-args-closure (fn [& predicate-results] (every? true? predicate-results))))

(defn handler-closure
  "Build a guarded handler closure for generic method bodies."
  [f]
  (closure/guarded-primitive-closure f))

(defn match-cells
  "Applicability object for generic handlers.

  `predicate-closures` are ordered per argument. `matcher-closure` receives the
  predicate results and decides whether the handler is applicable."
  ([predicate-closures]
   (match-cells predicate-closures all-args-match-closure))
  ([predicate-closures matcher-closure]
   {:predicate-closures (vec predicate-closures)
    :matcher-closure matcher-closure}))

(defn match-cells-pred
  "Applicability object from ordinary predicate functions."
  [& predicates]
  (match-cells (mapv predicate-closure predicates)))
