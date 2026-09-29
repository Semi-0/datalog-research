(ns propagators.cells.value
  "Cell contents: plain payloads, with bool4 bottom/top sentinels."
  (:require [propagators.cells.bool4 :as bool4]))

(def nothing bool4/nothing)
(def contradiction bool4/contradiction)

(defn contradiction-with-provenance
  [provenance]
  [:contradiction {:provenance (set provenance)}])

(defn contradiction-provenance
  [x]
  (if (and (vector? x)
           (= :contradiction (first x))
           (map? (second x)))
    (set (get (second x) :provenance #{}))
    #{}))

(defn add-contradiction-provenance
  [x provenance]
  (contradiction-with-provenance
   (into (contradiction-provenance x) provenance)))

(def nothing? bool4/nothing?)
(def contradiction? bool4/contradiction?)
(defmulti unusable?
  "Whether a datum is unusable for computation, not whether to transport it."
  class)

(defmethod unusable? :default [x] (bool4/unusable? x))

(defmulti any-unusable-values?
  "Tuple readiness. Layered implementations additionally check shared sources."
  (fn [& values]
    (if (some map? values) :compound :plain)))

(defmethod any-unusable-values? :default [& values]
  (boolean (some unusable? values)))
(defn value-payload [x] (when-not (unusable? x) x))
(defn cell-value-equal? [a b] (= a b))

;; if cell does not have annotation then it is nothing
