(ns propagators.cells.value
  "Cell contents: plain payloads, with `nothing` and `contradiction` sentinels."
  (:require [propagators.helpers.tagged :refer [tagged?]]))

(def nothing [:nothing])
(def contradiction [:contradiction])

(def nothing? (tagged? :nothing))
(def contradiction? (tagged? :contradiction))
(defn unusable? [x] (or (nothing? x) (contradiction? x)))
(defn any-unusable-values? [& values] (boolean (some unusable? values)))
(defn value-payload [x] (when-not (unusable? x) x))
(defn cell-value-equal? [a b] (= a b))
