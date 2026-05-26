(ns propagators.helpers.tagged
  "Curried predicates for `[:tag …]` vector data.")

(defn tagged?
  "Tag check constructor: `((tagged? :cell) x)`."
  [tag]
  (fn [x]
    (and (vector? x) (= tag (first x)))))
