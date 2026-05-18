(ns propagators.core
  (:require [propagators.cell :refer [->Cell cell-snapshot]]
            [propagators.cell-merge :refer [cell-merge cell-strongest cell-updated? handle-contradiction]]
            [propagators.cell-value :refer [contradiction?]]
            [propagators.graph :refer [node-inputs node-outputs]]))

;; experiments of propagator system which decouples network declaration from network evaluation
;; 4 core function of propagators
;; 1. networked semantics DONE
;; 2. fixpoint evaluation DONE
;; 3. partial information partialy
;; 4. dependence tracking nah

(def empty-tasks [])

(defn eval-cell [node update env graph]
  (let [id (:id node)
        {:keys [content strongest]} (get env id)
        content-update (cell-merge content update)
        strongest-update (cell-strongest content-update)
        updated-env (assoc env id (->Cell content-update strongest-update))
        next-tasks (vec (node-outputs graph node))]
    (if (cell-updated? strongest-update strongest)
      (if (contradiction? strongest-update)
        [next-tasks (handle-contradiction node updated-env)]
        [next-tasks updated-env])
      [empty-tasks updated-env])))
;; is and os could be a multiset
;; [node message]
(defn eval-cells [diffs env graph]
  (loop [ds diffs
         tasks []
         e env]
    (if (empty? ds)
      [tasks e]
      (let [[node message] (first ds)
            [poped new-e] (eval-cell node message e graph)]
        (recur (rest ds) (concat tasks poped) new-e)))))

(declare run-tasks)

(defn eval-propagator [current tasks graph env]
  (let [input-nodes (node-inputs graph current)
        output-nodes (node-outputs graph current)
        inputs (map (cell-snapshot env) input-nodes)
        outputs (map (cell-snapshot env) output-nodes)
        f (:f (get env (:id current)))
        env-diffs (f inputs outputs)
        [poped new-env] (eval-cells env-diffs env graph)]
    (run-tasks (concat poped tasks) [graph new-env])))

(defn run-tasks [tasks [graph env]]
  (if (empty? tasks) ;;fixpoint?
    [graph env]
    (eval-propagator (first tasks) (rest tasks) graph env)))
