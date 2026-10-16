(ns propagators.cells.error-layer
  "Error evidence on contradictions, independent of compound topology."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]))

(defn errors-of
  [datum]
  (if (and (value/contradiction? datum)
           (vector? datum)
           (map? (second datum)))
    (get (second datum) :error #{})
    #{}))

(defn attach
  "Attach error records without discarding existing contradiction provenance."
  [datum errors]
  (let [combined (set/union (errors-of datum) (set errors))
        result (value/add-contradiction-provenance datum combined)]
    (if (empty? combined)
      result
      (assoc-in result [1 :error] combined))))

(defn merge-contradictions
  "Union error evidence and ordinary contradiction provenance."
  [left right]
  (attach (value/add-contradiction-provenance
           left (value/contradiction-provenance right))
          (errors-of right)))
