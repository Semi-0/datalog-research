(ns propagators.helpers.network
  "Network shape helpers."
  (:require [propagators.graph :refer [graph?]]))

(def empty-env {})

(defn network?
  ([x]
   (and (vector? x)
        (= (count x) 2)
        (graph? (first x))
        (map? (second x)))))
