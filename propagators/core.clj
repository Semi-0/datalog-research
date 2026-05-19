(ns propagators.core
  (:require [propagators.cells :refer [->Cell cell-snapshot]]
            [propagators.cells.merge :refer [cell-merge cell-strongest cell-updated? handle-contradiction]]
            [propagators.cells.value :refer [contradiction?]]
            [propagators.graph :refer [node-inputs node-outputs]]
            [propagators.helpers.task-queue :as tq]))

(defn eval-cell [node update env graph]
  (let [id (:id node)
        {:keys [content strongest]} (get env id)
        content-update (cell-merge content update)
        strongest-update (cell-strongest content-update)
        updated-env (assoc env id (->Cell content-update strongest-update))
        next-nodes (node-outputs graph node)
        next-tasks (tq/enqueue-all tq/empty-queue next-nodes)]
    (if (cell-updated? strongest-update strongest)
      (if (contradiction? strongest-update)
        (handle-contradiction next-tasks node updated-env)
        [next-tasks updated-env])
      [tq/empty-queue updated-env])))

;; maybe this could be a generic dispatcher for lazied cell as well?
(defn eval-cells [diffs env graph]
  (loop [ds diffs
         tasks tq/empty-queue
         e env]
    (if (empty? ds)
      [tasks e]
      (let [[node message] (first ds)
            [poped new-e] (eval-cell node message e graph)]
        (recur (rest ds) (tq/merge-queues tasks poped) new-e)))))

(defn eval-propagator [current tasks graph env]
  (let [input-nodes (node-inputs graph current)
        output-nodes (node-outputs graph current)
        inputs (map (cell-snapshot env) input-nodes)
        outputs (map (cell-snapshot env) output-nodes)
        f (:f (get env (:id current)))
        env-diffs (f inputs outputs)
        [poped new-env] (eval-cells env-diffs env graph)]
    [(tq/merge-queues tasks poped) [graph new-env]]))

(defn run-tasks [tasks [graph env]]
  (loop [ts (tq/into-queue tasks)
         g graph
         e env]
    (if (tq/queue-empty? ts)
      [g e]
      (let [[current remaining] (tq/pop-task ts)
            [*t [*g *e]] (eval-propagator current remaining g e)]
        (recur *t *g *e)))))
