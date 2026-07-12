(ns propagators.core
  "Propagation scheduler (eval cells/propagators, run task queue)."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.message :refer [message-id message-value]]
            [propagators.network :as net]
            [propagators.gur.flat.effects :as effects]
            [propagators.propagator :as prop]))

(defn- maybe-register-subenv
  [n id strongest]
  (let [register (requiring-resolve 'propagators.gur.subenv/maybe-register-subenv)]
    (register n id strongest)))

(defn eval-cell [id msg n]
  (let [old (net/env-get (net/net-env n) id)
        update (message-value msg)]
    (if (and (cell/cell? old)
             (= update (cell/cell-content old)))
      [tq/empty-queue n]
      (let [old-strongest (cell/cell-strongest old)
            content' (merge/cell-merge (cell/cell-content old) update n)
            strongest' (merge/strongest-value content' n)
            n' (-> n
                   (net/assoc-net-cell id (cell/cell (cell/cell-name old)
                                                     content'
                                                     strongest'))
                   (maybe-register-subenv id strongest'))
            node (graph/get-node (net/net-graph n') id)
            next-tasks (tq/enqueue-all tq/empty-queue (graph/node-output-ids node))]
        (if (merge/cell-updated? strongest' old-strongest n)
          (if (value/contradiction? strongest')
            (let [[tasks env] (merge/handle-contradiction next-tasks id (net/net-env n'))]
              [tasks (net/net-with-env n' env)])
            [next-tasks n'])
          [tq/empty-queue n'])))))

(defn eval-cell*
  "Evaluate `msg`, routing lexical sub-env refs through the current network dict.

  For ordinary node ids this delegates to `eval-cell`. For sub-env dispatch keys
  the owner network-valued cell is the only parent cell evaluated.
  "
  [directory msg n]
  (let [dispatch-eval (requiring-resolve 'propagators.gur.subenv/eval-cell*)]
    (dispatch-eval directory msg n)))

(defn eval-cells [messages n]
  (loop [ms messages
         tasks tq/empty-queue
         n' n]
    (if (empty? ms)
      [tasks n']
      (let [msg (first ms)
            [poped new-n] (eval-cell* (net/net-dict-or-empty n') msg n')]
        (recur (rest ms) (tq/merge-queues tasks poped) new-n)))))

(defn eval-effects
  "Apply kernel declaration effects and then merge any effect-produced messages."
  [effects n]
  (if (empty? effects)
    [tq/empty-queue n]
    (let [{effect-tasks :tasks n* :net messages :messages}
          (effects/apply-effects tq/empty-queue n effects)
          [message-tasks n**] (if (empty? messages)
                                [tq/empty-queue n*]
                                (eval-cells messages n*))]
      [(tq/merge-queues effect-tasks message-tasks) n**])))

(defn eval-activation-result
  [ret n]
  (let [{:keys [messages effects]} (effects/normalize-activation-return ret)
        [effect-tasks n*] (eval-effects effects n)
        [message-tasks n**] (if (empty? messages)
                              [tq/empty-queue n*]
                              (eval-cells messages n*))]
    [(tq/merge-queues effect-tasks message-tasks) n**]))

(defn eval-propagator [current-id tasks n]
  (let [g (net/net-graph n)
        e (net/net-env n)
        current-node (graph/get-node g current-id)
        inputs (graph/node-input-ids current-node)
        outputs (graph/node-output-ids current-node)
        f (prop/prop-f (net/env-get e current-id))
        ret (f inputs outputs n)
        [poped new-net] (eval-activation-result ret n)]
    [(tq/merge-queues tasks poped) new-net]))

(defn run-tasks [tasks n]
  (loop [ts (tq/into-queue tasks)
         n' n]
    (if (tq/queue-empty? ts)
      n'
      (let [[current-id remaining] (tq/pop-task ts)
            [*t *n] (eval-propagator current-id remaining n')]
        (recur *t *n)))))
