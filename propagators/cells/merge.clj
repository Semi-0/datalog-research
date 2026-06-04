(ns propagators.cells.merge
  "Merge, strongest selection, and contradiction handling."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound_subnet :as subnet]
            [propagators.datastructures.compound_subnet_state :as state]
            [propagators.datastructures.compound_update :as update]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.datastructures.named-network :as named]
            [propagators.datastructures.reducer-subnet :as reducer]))

(def cell-equal? value/cell-value-equal?)

(declare strongest-value)

(defmulti cell-updated?
  (fn [new old _network]
    (cond
      (and (state/compound-subnet-state? new)
           (state/compound-subnet-state? old))
      :compound-subnet-state

      (or (reducer/reducer-subnet? new)
          (reducer/reducer-subnet? old))
      :reducer-subnet

      (or (named/named-network? new)
          (named/named-network? old)
          (evidence/evidence-set? new)
          (evidence/evidence-set? old))
      :named-network

      :else
      :default)))

(defmethod cell-updated? :default
  [new old _network]
  (not (cell-equal? new old)))

;; :compound-subnet-state — compare not-yet-executed structural state (subnet + out-ids).
(defmethod cell-updated? :compound-subnet-state
  [new old _network]
  (not (cell-equal? new old)))

(defmethod cell-updated? :reducer-subnet
  [new old network]
  (not (cell-equal? (strongest-value new network)
                    (strongest-value old network))))

(defn- named-strongest-equal? [new old]
  (cond
    (and (named/named-network? new)
         (named/named-network? old))
    (and (= true (named/named-network->= new old))
         (= true (named/named-network->= old new)))

    :else
    (cell-equal? new old)))

(defmethod cell-updated? :named-network
  [new old network]
  (let [new* (strongest-value new network)
        old* (strongest-value old network)]
    (not (named-strongest-equal? new* old*))))

(defmulti cell-merge
  (fn [_content update _network]
    (cond
      (update/compound-sync? update) :compound-sync
      (update/compound-data? update) :compound-data
      (reducer/reducer-subnet? update) :reducer-subnet
      (evidence/evidence-set? update) :named-network
      (named/named-network? update) :named-network
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

(defmethod cell-merge :named-network
  [content update _network]
  (let [content* (if (or (value/nothing? content)
                         (named/named-network? content)
                         (evidence/evidence-set? content))
                   content
                   ((requiring-resolve
                     'propagators.datastructures.compound-object/compound-object)
                    content))]
    (cond
      (value/contradiction? content*) value/contradiction
      (value/contradiction? update) value/contradiction
      (value/nothing? update) (evidence/merge-evidence value/nothing content*)
      (or (value/nothing? content*)
          (named/named-network? content*)
          (evidence/evidence-set? content*)) (evidence/merge-evidence content* update)
      :else value/contradiction)))

(defmethod cell-merge :reducer-subnet
  [content update _network]
  (cond
    (value/nothing? content) update
    (value/nothing? update) content
    (value/contradiction? content) value/contradiction
    (value/contradiction? update) value/contradiction
    (= content update) content
    (and (reducer/reducer-subnet? content)
         (reducer/reducer-subnet? update)
         (= (reducer/merge-net content) (reducer/merge-net update))
         (= (reducer/init content) (reducer/init update))
         (named/named-network? (reducer/source content))
         (named/named-network? (reducer/source update))
         (= true (named/named-network->= (reducer/source update)
                                         (reducer/source content))))
    update
    (and (reducer/reducer-subnet? content)
         (reducer/reducer-subnet? update)
         (= (reducer/merge-net content) (reducer/merge-net update))
         (= (reducer/init content) (reducer/init update))
         (named/named-network? (reducer/source content))
         (named/named-network? (reducer/source update))
         (= true (named/named-network->= (reducer/source content)
                                         (reducer/source update))))
    content
    :else value/contradiction))

(def generic-merge cell-merge)

(defn merge-cell-entry
  "Merge `update` into cell `entry`, returning a cell with refreshed strongest value."
  [entry update network]
  (let [content' (cell-merge (cell/cell-content entry) update network)
        strongest' (strongest-value content' network)]
    (cell/cell content' strongest')))

(defmulti strongest-value
  (fn [x _network]
    (cond
      (evidence/evidence-set? x) :named-network-evidence
      (cell/cell? x) :cell
      (reducer/reducer-subnet? x) :reducer-subnet
      (state/compound-subnet-state? x) :compound-subnet
      (named/named-network? x) :named-network
      :else :content)))

(defmethod strongest-value :cell
  [c _network]
  (cell/cell-strongest c))

(defmethod strongest-value :content
  [x _network]
  x)

(defmethod strongest-value :named-network
  [content _network]
  content)

(defmethod strongest-value :named-network-evidence
  [content _network]
  (evidence/strongest content))

(defmethod strongest-value :reducer-subnet
  [content _network]
  (reducer/strongest content))

;; :compound-subnet — structural state only; effectful run in c:linked-list.
(defmethod strongest-value :compound-subnet
  [content _network]
  content)

(defmulti handle-contradiction
  (fn [tasks _node env] [tasks env]))

(defmethod handle-contradiction :default
  [tasks _node env]
  [tasks env])
