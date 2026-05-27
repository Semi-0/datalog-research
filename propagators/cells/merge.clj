(ns propagators.cells.merge
  "Merge, strongest selection, and contradiction handling."
  (:require [clojure.set :as set]
            [propagators.cells.avatar :as avatar]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound_strongest_result :as strongest]
            [propagators.datastructures.compound_subnet :as subnet]
            [propagators.datastructures.compound_subnet_state :as state]
            [propagators.datastructures.compound_update :as update]))

(def cell-equal? value/cell-value-equal?)

(defn- run-subnet-effectful
  "Run subnet and return strongest result."
  [subnet outer-ids]
  (let [run-tasks (requiring-resolve 'propagators.core/run-tasks)
        updated* (atom #{})
        {:keys [subnet tasks]} (subnet/subnet-effectful-tasks subnet outer-ids updated*)]
    (strongest/strongest-result (run-tasks tasks subnet) updated*)))

(defn- compound-subnet-strongest
  "Run internal subnet effectfully; return strongest result."
  [state _network]
  (run-subnet-effectful (state/state-subnet state)
                        (vec (state/state-out-ids state))))

(defmulti cell-updated?
  (fn [new old _network]
    (if (and (strongest/compound-strongest-result? new)
             (strongest/compound-strongest-result? old))
      :compound-strongest
      :default)))

(defmethod cell-updated? :default
  [new old _network]
  (not (cell-equal? new old)))

(defmethod cell-updated? :compound-strongest
  [new old _network]
  (let [subnet-n (strongest/strongest-subnet new)
        subnet-o (strongest/strongest-subnet old)
        updated*-n (strongest/strongest-updated* new)
        updated*-o (strongest/strongest-updated* old)
        ids (set/union @updated*-n @updated*-o)]
    (boolean
     (some (fn [outer-id]
             (not (cell-equal?
                   (avatar/avatar-strongest subnet-n outer-id)
                   (avatar/avatar-strongest subnet-o outer-id))))
           ids))))

(defmulti cell-merge
  (fn [_content update _network]
    (if (update/compound-data? update)
      :compound-data
      :default)))

(defmethod cell-merge :default
  [content update _network]
  (cond
    (value/nothing? content) update
    (value/nothing? update) content
    (value/contradiction? content) value/contradiction
    (value/contradiction? update) value/contradiction
    (= content update) content
    :else value/contradiction))

(defmethod cell-merge :compound-data
  [content update network]
  (cond
    (value/contradiction? content) value/contradiction
    :else
    (let [state (if (value/nothing? content)
                   (state/empty-compound-subnet)
                   content)]
      (subnet/merge-compound-data state update network))))

(def generic-merge cell-merge)

(defmulti strongest-value
  (fn [x _network]
    (cond
      (cell/cell? x) :cell
      (state/compound-subnet-state? x) :compound-subnet
      :else :content)))

(defmethod strongest-value :cell
  [c _network]
  (cell/cell-strongest c))

(defmethod strongest-value :content
  [x _network]
  x)

(defmethod strongest-value :compound-subnet
  [content network]
  (compound-subnet-strongest content network))

(defmulti handle-contradiction
  (fn [tasks _node env] [tasks env]))

(defmethod handle-contradiction :default
  [tasks _node env]
  [tasks env])
