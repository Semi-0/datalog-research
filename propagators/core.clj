(ns propagators.core
  "Propagation scheduler (eval cells/propagators, run task queue)."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.message :refer [message-id message-value]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn eval-cell [id msg n]
  (let [old (net/env-get (net/net-env n) id)
        old-strongest (merge/strongest-value old n)
        content' (merge/cell-merge (cell/cell-content old) (message-value msg) n)
        strongest' (merge/strongest-value content' n)
        n' (net/assoc-net-cell n id (cell/cell content' strongest'))
        node (graph/get-node (net/net-graph n') id)
        next-tasks (tq/enqueue-all tq/empty-queue (graph/node-output-ids node))]
    (if (merge/cell-updated? strongest' old-strongest n)
      (if (value/contradiction? strongest')
        (let [[tasks env] (merge/handle-contradiction next-tasks id (net/net-env n'))]
          [tasks (net/net-with-env n' env)])
        [next-tasks n'])
      [tq/empty-queue n])))

(defn eval-cells [messages n]
  (loop [ms messages
         tasks tq/empty-queue
         n' n]
    (if (empty? ms)
      [tasks n']
      (let [msg (first ms)
            [poped new-n] (eval-cell (message-id msg) msg n')]
        (recur (rest ms) (tq/merge-queues tasks poped) new-n)))))

(defn eval-propagator [current-id tasks n]
  (let [g (net/net-graph n)
        e (net/net-env n)
        current-node (graph/get-node g current-id)
        inputs (graph/node-input-ids current-node)
        outputs (graph/node-output-ids current-node)
        f (prop/prop-f (net/env-get e current-id))
        messages (f inputs outputs n)
        [poped new-net] (eval-cells messages n)]
    [(tq/merge-queues tasks poped) new-net]))

(defn run-tasks [tasks n]
  (loop [ts (tq/into-queue tasks)
         n' n]
    (if (tq/queue-empty? ts)
      n'
      (let [[current-id remaining] (tq/pop-task ts)
            [*t *n] (eval-propagator current-id remaining n')]
        (recur *t *n)))))
