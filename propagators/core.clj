(ns propagators.core
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :refer [handle-contradiction]]
            [propagators.cells.snapshot :refer [cell-snapshot]]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.message :refer [message-id message-value]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn eval-cell [node msg n]
  (let [id (graph/node-id node)
        old (net/env-get (net/net-env n) id)
        old-strongest (cell/cell-strongest old)
        content' (value/cell-merge (cell/cell-content old) (message-value msg))
        strongest' (cell/cell-strongest content')
        n' (net/assoc-net-cell n id (cell/cell content' strongest'))
        next-tasks (tq/enqueue-all tq/empty-queue (graph/node-outputs (net/net-graph n') node))]
    (if (value/cell-updated? strongest' old-strongest)
      (if (value/contradiction? strongest')
        (let [[tasks env] (handle-contradiction next-tasks node (net/net-env n'))]
          [tasks (net/net-with-env n' env)])
        [next-tasks n'])
      [tq/empty-queue n'])))

(defn eval-cells [messages n]
  (loop [ms messages
         tasks tq/empty-queue
         n' n]
    (if (empty? ms)
      [tasks n']
      (let [msg (first ms)
            node (graph/get-node (net/net-graph n') (message-id msg))
            [poped new-n] (eval-cell node msg n')]
        (recur (rest ms) (tq/merge-queues tasks poped) new-n)))))

(defn eval-propagator [current tasks n]
  (let [g (net/net-graph n)
        e (net/net-env n) 
        inputs  (graph/node-inputs g current)
        outputs (graph/node-outputs g current)
        f (prop/prop-f (net/env-get e (graph/node-id current)))
        messages (f inputs outputs n)
        [poped new-net] (eval-cells messages n)]
    [(tq/merge-queues tasks poped) new-net]))

(defn run-tasks [tasks n]
  (loop [ts (tq/into-queue tasks)
         n' n]
    (if (tq/queue-empty? ts)
      n'
      (let [[current remaining] (tq/pop-task ts)
            [*t *n] (eval-propagator current remaining n')]
        (recur *t *n)))))
