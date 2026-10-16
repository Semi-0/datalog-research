(ns propagators.cells.merge-failure
  "Pure diagnostics at the total cell-merge boundary."
  (:require [propagators.cells.error-layer :as error-layer]))

(defn contradiction
  [content update error]
  (error-layer/attach
   (error-layer/merge-contradictions content update)
   #{{:operation :cell/merge
      :operands (set [content update])
      :exception/class (.getName (class error))
      :exception/message (.getMessage ^Throwable error)
      :exception/data (ex-data error)}}))
