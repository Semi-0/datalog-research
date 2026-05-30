(ns propagators.cells.value
  "Cell contents: plain payloads, with bool4 bottom/top sentinels."
  (:require [propagators.cells.bool4 :as bool4]))

(def nothing bool4/nothing)
(def contradiction bool4/contradiction)

(def nothing? bool4/nothing?)
(def contradiction? bool4/contradiction?)
(defn unusable? [x] (bool4/unusable? x))
(defn any-unusable-values? [& values] (boolean (some unusable? values)))
(defn value-payload [x] (when-not (unusable? x) x))
(defn cell-value-equal? [a b] (= a b))

;; if cell does not have annotation then it is nothing
