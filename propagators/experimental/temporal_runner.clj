(ns propagators.experimental.temporal-runner
  "Opt-in temporal evidence evaluation, composed through the runner constructor.
  The same immutable cell evaluator handles ingress and propagator publications."
  (:require [propagators.cell-evaluator :as evaluator]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.temporal-behavior :as temporal]
            [propagators.network :as net]
            [propagators.runner :as runner]
            [propagators.runner-constructor :as constructor]))

(defn- temporal-evidence? [v]
  (and (collection/content? v)
       (some #(temporal/temporal? (:base %)) (:support/observations v))))

(defn merge-content [content update network]
  (cond
    (reducer/raw-update? content update)
    (reducer/merge-content content update merge/cell-merge network)

    (and (or (value/nothing? content) (collection/content? content))
         (or (value/nothing? update) (collection/content? update))
         (or (temporal-evidence? content) (temporal-evidence? update)))
    (temporal/join-evidence content update)

    :else (merge/cell-merge content update network)))

(def evaluate-cell
  (evaluator/evaluator
   merge-content
   (fn [outputs network]
     (filter #(-> (net/network-env-lookup network %) :activate meta
                  :propagators.propagator/retained-inputs?) outputs))))

(defn evaluate-patches [evaluated network continuations]
  (binding [evaluator/*evaluate-cell* evaluate-cell]
    (runner/evaluate-patches evaluated network continuations)))

(defn iterator []
  (constructor/network-iterator-constructor
   runner/fifo-task-policy runner/evaluate-propagator evaluate-patches))

(def evaluation-options
  {:cell-evaluator evaluate-cell :iterator-factory iterator})
