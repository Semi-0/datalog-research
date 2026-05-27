(ns propagators.cells.compound-merge
  "Compound-data defmethods for `propagators.cells.merge` (loaded after merge + subnet)."
  (:require [clojure.set :as set]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :refer [run-tasks]]
            [propagators.datastructures.compound_subnet :as subnet]))

(defn run-subnet-effectful
  "Run subnet and return strongest result."
  [subnet outer-ids]
  (let [updated* (atom #{})
        {:keys [subnet tasks]} (subnet/subnet-effectful-tasks subnet outer-ids updated*)]
    (subnet/strongest-result (run-tasks tasks subnet) updated*)))

(defn compound-subnet-strongest
  "Run internal subnet effectfully; return strongest result."
  [state _network]
  (run-subnet-effectful (subnet/state-subnet state)
                        (vec (subnet/state-out-ids state))))

(defmethod merge/cell-updated? :compound-strongest
  [new old _network]
  (let [subnet-n (subnet/strongest-subnet new)
        subnet-o (subnet/strongest-subnet old)
        updated*-n (subnet/strongest-updated* new)
        updated*-o (subnet/strongest-updated* old)
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
