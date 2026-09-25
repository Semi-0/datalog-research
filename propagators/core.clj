(ns propagators.core
  "Direct activation-result and cell evaluation helpers."
  (:require [propagators.cell-evaluator :as cell-evaluator]
            [propagators.helpers.task-queue :as tq]
            [propagators.network-patch :as patch]))

(def eval-cell cell-evaluator/evaluate-cell)

(defn eval-cell*
  [_directory msg n]
  (cell-evaluator/evaluate msg n))

(def eval-cells cell-evaluator/evaluate-all)

(defn eval-effects
  "Apply declaration patches and return their tasks plus updated network."
  [effects n]
  (reduce (fn [[tasks network] effect]
            (let [[new-tasks next-network]
                  (patch/apply-declaration-patch nil effect network)]
              [(tq/merge-queues tasks new-tasks) next-network]))
          [tq/empty-queue n]
          effects))

(defn eval-activation-result
  [ret n]
  (let [{:keys [messages effects]} (patch/normalize-activation-return ret)
        [effect-tasks n*] (eval-effects effects n)
        [message-tasks n**] (if (empty? messages)
                              [tq/empty-queue n*]
                              (eval-cells messages n*))]
    [(tq/merge-queues effect-tasks message-tasks) n**]))
