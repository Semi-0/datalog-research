(ns propagators.network
  "Network shape + primitive sync propagator. Constructors and helpers live in sibling ns."
  (:require [propagators.graph :refer [graph?]]
            [propagators.network.constructors :as constructors]
            [propagators.network.helpers :as helpers]))

;; --- network ---

(defn network?
  ([x]
   (and (vector? x)
        (= (count x) 2)
        (graph? (first x))
        (map? (second x)))))

;; --- re-exports: constructors ---

(def construct-cell constructors/construct-cell)
(def construct-propagator constructors/construct-propagator)
(def primitive-propagator constructors/primitive-propagator)

;; --- re-exports: helpers (for callers building custom propagators) ---

(def make-message helpers/make-message)
(def strongest-from-snapshot helpers/strongest-from-snapshot)
(def as-message helpers/as-message)

;; --- built-in propagator ---

(def p:id (primitive-propagator (fn [x] x)))
