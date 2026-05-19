(ns propagators.cells.diff
  "Boundary diff: emit cell messages only when :strongest changed."
  (:require [propagators.cells.merge :refer [cell-updated?]]))

(defn- make-message [node message]
  [node message])

(defn diff-cell [receiver sender]
  (let [[node-before before-cell] receiver
        [node-after after-cell] sender
        before-val (:strongest before-cell)
        after-val (:strongest after-cell)]
    (when-not (= (:id node-before) (:id node-after))
      (throw (ex-info "receiver/sender node mismatch"
                      {:before (:id node-before) :after (:id node-after)})))
    (when (cell-updated? after-val before-val)
      ;; we make in the node before 
      ;; so node in the simulation is isolated from the outside one
      (make-message node-before after-val))))

(defn diff-cells [receivers senders]
  (when (not= (count receivers) (count senders))
    (throw (ex-info "receiver/sender count mismatch"
                    {:receivers (count receivers) :senders (count senders)})))
  (keep identity (map diff-cell receivers senders)))
