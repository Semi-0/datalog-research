(ns propagators.cells.diff
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.snapshot :refer [snap-cell snap-id]]
            [propagators.cells.value :as value]
            [propagators.message :refer [message]]))

(defn diff-cell [receiver sender]
  (let [before-val (cell/cell-strongest (snap-cell receiver))
        after-val (cell/cell-strongest (snap-cell sender))]
    (when-not (= (snap-id receiver) (snap-id sender))
      (throw (ex-info "receiver/sender node mismatch"
                      {:before (snap-id receiver) :after (snap-id sender)})))
    (when (value/cell-updated? after-val before-val)
      (message (snap-id receiver) after-val))))

(defn diff-cells [receivers senders]
  (when (not= (count receivers) (count senders))
    (throw (ex-info "receiver/sender count mismatch"
                    {:receivers (count receivers) :senders (count senders)})))
  (keep identity (map diff-cell receivers senders)))
