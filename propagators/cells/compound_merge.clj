(ns propagators.cells.compound-merge
  "Compound-data defmethods for `propagators.cells.merge` (loaded after merge + subnet)."
  (:require [clojure.set :as set]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :refer [run-tasks]]
            [propagators.datastructures.compound_subnet :as subnet]))

(defn run-subnet-effectful
  "Run subnet and return `[subnet updated-cells-atom]`."
  [subnet outer-ids]
  (let [updated* (atom #{})
        {:keys [subnet tasks]} (subnet/subnet-effectful-tasks subnet outer-ids updated*)]
    [(run-tasks tasks subnet) updated*]))

(defn compound-subnet-strongest
  "Run internal subnet effectfully; return `[subnet updated-cells-atom]`."
  [[subnet out-ids] _network]
  (run-subnet-effectful subnet (vec out-ids)))

(defmethod merge/cell-updated? :compound-strongest
  [new old _network]
  (let [[subnet-n updated*-n] new
        [subnet-o updated*-o] old
        ids (set/union @updated*-n @updated*-o)]
    (boolean
     (some (fn [outer-id]
             (not (merge/cell-equal?
                   (subnet/avatar-strongest subnet-n outer-id)
                   (subnet/avatar-strongest subnet-o outer-id))))
           ids))))

(defmethod merge/cell-merge :compound-data
  [content update network]
  (cond
    (value/contradiction? content) value/contradiction
    :else
    (let [state (if (value/nothing? content)
                   (subnet/empty-compound-subnet)
                   content)]
      (subnet/merge-compound-data state update network))))

(defmethod merge/strongest-value :compound-subnet
  [content network]
  (compound-subnet-strongest content network))
