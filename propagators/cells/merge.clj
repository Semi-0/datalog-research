(ns propagators.cells.merge
  "Merge, strongest selection, and contradiction handling."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound_subnet :as subnet]
            [propagators.datastructures.compound_subnet_state :as state]
            [propagators.datastructures.compound_update :as update]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.datastructures.named-network :as named]
            [propagators.network :as net]
            [propagators.datastructures.reducer-subnet :as reducer]))

(def cell-equal? value/cell-value-equal?)

(declare strongest-value)

(defn- closure-value?
  [x]
  (and (map? x)
       (contains? x :f)
       (contains? x :net)))

(defn- compatible-closures?
  [a b]
  (and (closure-value? a)
       (closure-value? b)
       (= (:f a) (:f b))
       (= (:boundary a) (:boundary b))))

(defn- closure-net-equivalent?
  [a b]
  (and (= true (named/named-network->= (:net a) (:net b)))
       (= true (named/named-network->= (:net b) (:net a)))))

(defmulti cell-updated?
  (fn [new old _network]
    (cond
      (and (state/compound-subnet-state? new)
           (state/compound-subnet-state? old))
      :compound-subnet-state

      (or (reducer/reducer-subnet? new)
          (reducer/reducer-subnet? old))
      :reducer-subnet

      (or (closure-value? new)
          (closure-value? old))
      :closure

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

(defmethod cell-updated? :closure
  [new old _network]
  (not (and (compatible-closures? new old)
            (closure-net-equivalent? new old))))

(defn- named-strongest-equal? [new old]
  (cond
    (and (named/named-network? new)
         (named/named-network? old))
    (and (= true (named/named-network->= new old))
         (= true (named/named-network->= old new)))

    :else
    (cell-equal? new old)))

(defn- accumulating-gur-network?
  [x]
  (and (named/named-network? x)
       (contains? (net/net-dict-or-empty x) [:gur/accumulating :frames])))

(defn- accumulating-gur-fragment?
  [x]
  (and (named/named-network? x)
       (some (fn [k]
               (and (vector? k)
                    (= :gur/accumulating (first k))))
             (keys (net/net-dict-or-empty x)))))

(defn- network-vm-nested-delta?
  [x]
  (and (map? x)
       (= "propagators.network_vm.nested.NetworkDelta"
          (.getName (class x)))))

(defmethod cell-updated? :named-network
  [new old network]
  (let [new* (strongest-value new network)
        old* (strongest-value old network)]
    (if (or (accumulating-gur-network? new*)
            (accumulating-gur-network? old*))
      (not (identical? new* old*))
      (not (named-strongest-equal? new* old*)))))

(defmulti built-in-cell-merge
  (fn [_content update _network]
    (cond
      (update/compound-sync? update) :compound-sync
      (update/compound-data? update) :compound-data
      (reducer/reducer-subnet? update) :reducer-subnet
      (network-vm-nested-delta? update) :network-vm-nested-delta
      (or (closure-value? _content)
          (closure-value? update)) :closure
      (evidence/evidence-set? update) :named-network
      (named/named-network? update) :named-network
      :else :default)))

(defmethod built-in-cell-merge :default
  [content update _network]
  (cond
    (value/nothing? content) update
    (value/nothing? update) content
    (value/contradiction? content) value/contradiction
    (value/contradiction? update) value/contradiction
    (= content update) content
    :else value/contradiction))

(defmethod built-in-cell-merge :compound-data
  [content update network]
  (cond
    (value/contradiction? content) value/contradiction
    :else
    (let [state (if (value/nothing? content)
                   (state/empty-compound-subnet)
                   content)]
      (subnet/merge-compound-data state update network))))

(defmethod built-in-cell-merge :compound-sync
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

(defn- accessor-network-update?
  [update]
  (and (named/named-network? update)
       (let [accessor-network? (requiring-resolve
                                'propagators.datastructures.compound-object.merge/accessor-network?)]
         (accessor-network? update))))

(defn- normalize-named-network-content
  [content]
  (if (or (value/nothing? content)
          (named/named-network? content)
          (evidence/evidence-set? content))
    content
    ((requiring-resolve
      'propagators.datastructures.compound-object/compound-object)
     content)))

(defn- merge-accessor-network-content
  [content update]
  ((requiring-resolve
    'propagators.datastructures.compound-object.merge/merge-named-network-content)
   content
   update))

(defn- merge-accumulating-gur-fragment
  [content update]
  (let [current (if (evidence/evidence-set? content)
                  (evidence/strongest content)
                  content)]
    (cond
      (value/contradiction? current) value/contradiction
      (value/contradiction? update) value/contradiction
      (value/nothing? current) update
      (value/nothing? update) current
      (not (and (named/named-network? current)
                (named/named-network? update))) value/contradiction
      (= true (named/named-network->= current update)) current
      :else (named/join current update))))

(defmethod built-in-cell-merge :network-vm-nested-delta
  [content update _network]
  ((requiring-resolve
    'propagators.network-vm.nested/merge-network-delta-content)
   content
   update))

(defmethod built-in-cell-merge :named-network
  [content update _network]
  (if (accessor-network-update? update)
    (merge-accessor-network-content content update)
    (let [content* (normalize-named-network-content content)]
      (if (or (accumulating-gur-fragment? content*)
              (accumulating-gur-fragment? update))
        (merge-accumulating-gur-fragment content* update)
        (cond
          (value/contradiction? content*) value/contradiction
          (value/contradiction? update) value/contradiction
          (value/nothing? update) (evidence/merge-evidence value/nothing content*)
          (or (value/nothing? content*)
              (named/named-network? content*)
              (evidence/evidence-set? content*)) (evidence/merge-evidence content* update)
          :else value/contradiction)))))

(defmethod built-in-cell-merge :reducer-subnet
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

(defmethod built-in-cell-merge :closure
  [content update _network]
  (cond
    (value/nothing? content) update
    (value/nothing? update) content
    (value/contradiction? content) value/contradiction
    (value/contradiction? update) value/contradiction

    (compatible-closures? content update)
    (let [merged-net (named/join (:net content) (:net update))]
      (if (value/contradiction? merged-net)
        value/contradiction
        (assoc content :net merged-net)))

    :else value/contradiction))

(defn- protocol-handled?
  [result]
  (and (map? result)
       (true? (get result :propagators.cells.cell-protocol/handled?))))

(defn- protocol-handled-value
  [result]
  (get result :propagators.cells.cell-protocol/value))

(defn- protocol-cell-merge
  [content update network]
  (let [try-cell-merge
        (requiring-resolve
         'propagators.cells.cell-protocol/try-cell-merge)]
    (try-cell-merge network content update)))

(defn cell-merge
  [content update network]
  (if (= content update)
    content
    (let [protocol-result (protocol-cell-merge content update network)]
      (if (protocol-handled? protocol-result)
        (protocol-handled-value protocol-result)
        (built-in-cell-merge content update network)))))

(def generic-merge cell-merge)

(defn merge-cell-entry
  "Merge `update` into cell `entry`, returning a cell with refreshed strongest value."
  [entry update network]
  (let [content' (cell-merge (cell/cell-content entry) update network)
        strongest' (strongest-value content' network)]
    (cell/cell content' strongest')))

(defmulti built-in-strongest-value
  (fn [x _network]
    (cond
      (evidence/evidence-set? x) :named-network-evidence
      (cell/cell? x) :cell
      (reducer/reducer-subnet? x) :reducer-subnet
      (state/compound-subnet-state? x) :compound-subnet
      (named/named-network? x) :named-network
      :else :content)))

(defmethod built-in-strongest-value :cell
  [c _network]
  (cell/cell-strongest c))

(defmethod built-in-strongest-value :content
  [x _network]
  x)

(defmethod built-in-strongest-value :named-network
  [content _network]
  content)

(defmethod built-in-strongest-value :named-network-evidence
  [content _network]
  (evidence/strongest content))

(defmethod built-in-strongest-value :reducer-subnet
  [content _network]
  (reducer/strongest content))

;; :compound-subnet — structural state only; effectful run in c:linked-list.
(defmethod built-in-strongest-value :compound-subnet
  [content _network]
  content)

(defn- protocol-cell-strongest
  [content network]
  (let [try-cell-strongest
        (requiring-resolve
         'propagators.cells.cell-protocol/try-cell-strongest)]
    (try-cell-strongest network content)))

(defn strongest-value
  [content network]
  (if (cell/cell? content)
    (built-in-strongest-value content network)
    (let [protocol-result (protocol-cell-strongest content network)]
      (if (protocol-handled? protocol-result)
        (protocol-handled-value protocol-result)
        (built-in-strongest-value content network)))))

(defmulti handle-contradiction
  (fn [tasks _node env] [tasks env]))

(defmethod handle-contradiction :default
  [tasks _node env]
  [tasks env])
