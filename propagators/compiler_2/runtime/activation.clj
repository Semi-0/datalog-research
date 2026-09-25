(ns propagators.compiler-2.runtime.activation
  "Run declared activation propagators and the readers of boundary inputs."
  (:require [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.runner :as runner]))

(defn run-network
  [n inner-inputs prop-ids]
  (let [input-prop-ids (pop-inputs inner-inputs (net/net-graph n))
        tasks (tq/enqueue-all tq/empty-queue
                              (concat prop-ids input-prop-ids))]
    (runner/completed-network (runner/run-network tasks n))))
