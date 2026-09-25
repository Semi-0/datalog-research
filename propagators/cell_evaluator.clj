(ns propagators.cell-evaluator
  "Primitive message evaluation for immutable propagator networks.

  This namespace owns cell refinement and scoped message routing. It does not
  select or drain propagator tasks."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.message :as message]
            [propagators.network :as net]))

(defn- maybe-register-subenv
  [network id strongest]
  (let [register
        (requiring-resolve 'propagators.scoped-routing/maybe-register-subenv)]
    (register network id strongest)))

(defn evaluate-cell
  [id cell-message network]
  (let [old (net/env-get (net/net-env network) id)
        update (message/message-value cell-message)]
    (if (and (cell/cell? old)
             (= update (cell/cell-content old)))
      [tq/empty-queue network]
      (let [old-strongest (cell/cell-strongest old)
            content (merge/cell-merge (cell/cell-content old) update network)
            strongest (merge/strongest-value content network)
            updated-network
            (-> network
                (net/assoc-net-cell id (cell/cell (cell/cell-name old)
                                                  content
                                                  strongest))
                (maybe-register-subenv id strongest))
            node (graph/get-node (net/net-graph updated-network) id)
            next-tasks
            (tq/enqueue-all tq/empty-queue (graph/node-output-ids node))]
        (if (merge/cell-updated? strongest old-strongest network)
          (if (value/contradiction? strongest)
            (let [[tasks env]
                  (merge/handle-contradiction
                   next-tasks id (net/net-env updated-network))]
              [tasks (net/net-with-env updated-network env)])
            [next-tasks updated-network])
          [tq/empty-queue updated-network])))))

(defn evaluate
  "Apply a message, routing scoped targets through their owner cell."
  [cell-message network]
  (let [dispatch
        (requiring-resolve 'propagators.scoped-routing/evaluate)]
    (dispatch (net/net-dict-or-empty network) cell-message network)))

(defn evaluate-all
  [messages network]
  (loop [remaining (seq messages)
         tasks tq/empty-queue
         current network]
    (if (nil? remaining)
      [tasks current]
      (let [[new-tasks next-network] (evaluate (first remaining) current)]
        (recur (next remaining)
               (tq/merge-queues tasks new-tasks)
               next-network)))))
