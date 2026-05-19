(ns as-messages
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.snapshot :refer [snap-cell snap-id]]
            [propagators.message :refer [message]]))

(defn strongest-from-snapshot [snap]
  (cell/cell-strongest (snap-cell snap)))

(defn as-messages [output-snaps vals]
  (let [ids (mapv snap-id output-snaps)]
    (when (not= (count ids) (count vals))
      (throw (ex-info "output count mismatch"
                      {:outputs (count ids) :results (count vals)})))
    (mapv #(message %1 %2) ids vals)))
