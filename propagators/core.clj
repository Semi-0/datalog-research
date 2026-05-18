(ns propagators.core
  (:require [clojure.set :as set]
            [propagators.cell :refer [->Cell cell-snapshot]]
            [propagators.cell-merge :refer [cell-merge cell-strongest cell-updated? handle-contradiction]]
            [propagators.cell-value :refer [contradiction?]]
            [propagators.graph :refer [node-inputs node-outputs]]))

(def empty-tasks #{})

(defn eval-cell [node update env graph]
  (let [id (:id node)
        {:keys [content strongest]} (get env id)
        content-update (cell-merge content update)
        strongest-update (cell-strongest content-update)
        updated-env (assoc env id (->Cell content-update strongest-update))
        next-tasks (node-outputs graph node)]
    (if (cell-updated? strongest-update strongest)
      (if (contradiction? strongest-update) 
        (handle-contradiction next-tasks node updated-env)
        [next-tasks updated-env])
      [empty-tasks updated-env])))
;; is and os could be a multiset
;; [[node message]]
(defn eval-cells [diffs env graph]
  (loop [ds diffs
         tasks #{}
         e env]
    (if (empty? ds)
      [tasks e]
      (let [[node message] (first ds)
            [poped new-e] (eval-cell node message e graph)]
        (recur (rest ds) (set/union tasks poped) new-e)))))

(defn eval-propagator [current tasks graph env]
  (let [input-nodes (node-inputs graph current)
        output-nodes (node-outputs graph current)
        inputs (map (cell-snapshot env) input-nodes)
        outputs (map (cell-snapshot env) output-nodes)
        f (:f (get env (:id current)))
        env-diffs (f inputs outputs)
        [poped new-env] (eval-cells env-diffs env graph)]
    [(set/union poped tasks) [graph new-env]]))

;; we can even backtrack
(defn run-tasks [tasks [graph env]]
  (loop [ts (set tasks)
         g graph
         e env]
    (if (empty? ts) ;; fixpoint?
      [g e]
      (let [current (first ts)
            remaining (disj ts current)
            [*t [*g *e]] (eval-propagator current remaining g e)]
        (recur *t *g *e)))))
