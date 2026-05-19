(ns propagators.helpers.task-queue
  "Immutable FIFO propagator task queue. Dedupes by `(:id node)`; first schedule wins order.")

(def empty-queue
  {:task-queue/seen #{}
   :task-queue/q []})

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
  "Enqueue `node` if its `:id` is not already scheduled. Returns new queue."
  [q node]
  (let [id (:id node)]
    (if (contains? (seen-set q) id)
      q
      {:task-queue/seen (conj (seen-set q) id)
       :task-queue/q (conj (fifo q) node)})))

(defn enqueue-all [q nodes]
  (reduce enqueue q nodes))

(defn merge-queues
  "Enqueue every node from `b` into `a` (FIFO order preserved; `a` drains first)."
  [a b]
  (enqueue-all a (fifo b)))

(defn into-queue
  "Coerce `x` to a task queue: queue map, set, or seq of nodes."
  [x]
  (cond
    (task-queue? x) x
    (set? x) (enqueue-all empty-queue x)
    (sequential? x) (enqueue-all empty-queue x)
    :else (throw (ex-info "not a task queue" {:value x}))))

(defn pop-task
  "Returns `[node queue']` or `nil` when empty."
  [q]
  (when (seq (fifo q))
    (let [node (first (fifo q))
          id (:id node)]
      [node {:task-queue/seen (disj (seen-set q) id)
             :task-queue/q (vec (rest (fifo q)))}])))
