(ns propagators.application-debugger
  "Debugger adapter for procedure application trace metadata."
  (:require [propagators.application :as application]
            [propagators.datastructures.compound-object :as obj]
            [propagators.debugger :as debugger]))

(defmulti report!
  (fn [phase trace _network]
    [phase (:trace/type trace)]))

(defmethod report! :default
  [_phase _trace _network]
  nil)

(defmethod report! [:after-branches :layered]
  [_phase {:keys [result-bank-id slots]} n]
  (when (debugger/enabled?)
    (let [result-bank (application/cell-strongest-or-nothing n result-bank-id)]
      (doseq [layer-name slots]
        (debugger/report!
         :layered/layer
         {:layer layer-name
          :handler-result (obj/slot-value result-bank layer-name)})))))

(defmethod report! [:after-reducer :layered]
  [_phase {:keys [reduced-out-id]} n]
  (debugger/report!
   :layered/selected
   {:selected-value (application/cell-strongest-or-nothing n reduced-out-id)}))

(defmethod report! [:after-branches :generic]
  [_phase {:keys [branches]} n]
  (when (debugger/enabled?)
    (doseq [{:keys [slot-key
                    predicate-out-ids
                    match-out-id
                    filtered-ids
                    handler-out-id
                    result-bank-id]} branches]
      (let [result-bank (application/cell-strongest-or-nothing n result-bank-id)]
        (debugger/report!
         :generic/method
         {:method-key slot-key
          :predicate-results (mapv #(application/cell-strongest-or-nothing n %)
                                   predicate-out-ids)
          :matched? (application/cell-strongest-or-nothing n match-out-id)
          :filtered-args (mapv #(application/cell-strongest-or-nothing n %)
                               filtered-ids)
          :handler-result (application/cell-strongest-or-nothing n handler-out-id)
          :result-bank-value (obj/slot-value result-bank slot-key)})))))

(defmethod report! [:after-reducer :generic]
  [_phase {:keys [reduced-out-id]} n]
  (debugger/report!
   :generic/selected
   {:selected-value (application/cell-strongest-or-nothing n reduced-out-id)}))
