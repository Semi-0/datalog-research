(ns propagators.cells.merge
  "Merge, strongest selection, and contradiction handling."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound_subnet :as subnet]
            [propagators.datastructures.compound_subnet_state :as state]
            [propagators.datastructures.compound_update :as update]))

(def cell-equal? value/cell-value-equal?)

(defmulti cell-updated?
  (fn [new old _network]
    (if (and (state/compound-subnet-state? new)
             (state/compound-subnet-state? old))
      :compound-subnet-state
      :default)))

(defmethod cell-updated? :default
  [new old _network]
  (not (cell-equal? new old)))

;; :compound-subnet-state — compare not-yet-executed structural state (subnet + out-ids).
(defmethod cell-updated? :compound-subnet-state
  [new old _network]
  (not (cell-equal? new old)))

(defmulti cell-merge
  (fn [_content update _network]
    (cond
      (update/compound-sync? update) :compound-sync
      (update/compound-data? update) :compound-data
      :else :default)))

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

(defmethod cell-merge :compound-sync
  [content update network]
  (cond
    (value/contradiction? content) value/contradiction
    (and (not (value/nothing? content))
         (not (state/compound-subnet-state? content))) value/contradiction
    :else
    (let [state (if (value/nothing? content)
                  (state/empty-compound-subnet)
                  content)]
      (subnet/merge-compound-sync state update network))))

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

;; :compound-subnet — structural state only; effectful run in c:linked-list.
(defmethod strongest-value :compound-subnet
  [content _network]
  content)

(defmulti handle-contradiction
  (fn [tasks _node env] [tasks env]))

(defmethod handle-contradiction :default
  [tasks _node env]
  [tasks env])
