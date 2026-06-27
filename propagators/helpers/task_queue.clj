(ns propagators.helpers.task-queue
  "Immutable FIFO propagator task queue. Dedupes by node-id token; first schedule wins order."
  (:require [propagators.ids :refer [node-id?]]))

(def empty-queue
  {:task-queue/seen #{}
   :task-queue/q clojure.lang.PersistentQueue/EMPTY})

(defn task-queue? [x]
  (and (map? x)
       (contains? x :task-queue/seen)
       (contains? x :task-queue/q)))

(defn- seen-set [q]
  (:task-queue/seen q))

(defn- fifo [q]
  (:task-queue/q q))

(defn queue-empty? [q]
  (clojure.core/empty? (fifo q)))

(defn enqueue
  "Enqueue `node-id` if not already scheduled. Returns new queue."
  [q node-id]
  (when-not (node-id? node-id)
    (throw (ex-info "expected node-id token" {:node-id node-id})))
  (if (contains? (seen-set q) node-id)
    q
    {:task-queue/seen (conj (seen-set q) node-id)
     :task-queue/q (conj (fifo q) node-id)}))

(defn enqueue-all [q node-ids]
  (reduce enqueue q (if (set? node-ids)
                      (sort-by pr-str node-ids)
                      node-ids)))

(defn merge-queues
  "Enqueue every node-id from `b` into `a` (FIFO order preserved; `a` drains first)."
  [a b]
  (enqueue-all a (fifo b)))

(defn into-queue
  "Coerce `x` to a task queue: queue map, set, or seq of node-id tokens."
  [x]
  (cond
    (task-queue? x) x
    (set? x) (enqueue-all empty-queue x)
    (sequential? x) (enqueue-all empty-queue x)
    :else (throw (ex-info "not a task queue" {:value x}))))

(defn pop-task
  "Returns `[node-id queue']` or `nil` when empty."
  [q]
  (when (seq (fifo q))
    (let [node-id (peek (fifo q))]
      [node-id {:task-queue/seen (disj (seen-set q) node-id)
                :task-queue/q (pop (fifo q))}])))
