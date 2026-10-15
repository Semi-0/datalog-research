(ns graph.vijual.layout.stress-flow
  "Optional core.async boundary; numerical sweeps remain synchronous."
  (:require [differential-dataflow.stream-ops :as stream]
            [graph.vijual.layout.node-model :as model]
            [graph.vijual.layout.stress-session :as session]))

(defn transition
  "Process {:changes transaction :iterations n :tolerance t}. Relaxation is
  explicit: an omitted iteration budget means zero iterations. Invalid commands
  emit an error and retain the last valid state. One output per command."
  [state command]
  (try
    (model/require-valid! (and (map? command)
                              (every? #{:changes :iterations :tolerance} (keys command)))
                          "Invalid layout command" {:command command})
    (let [edited (session/update-layout state (get command :changes {}))
          advanced (session/advance-layout edited
                                           (merge {:iterations 0}
                                                  (select-keys command [:iterations :tolerance])))
          deltas (model/node-deltas (:nodes state) (:nodes advanced) (get-in state [:opts :id-fn]))
          old-positions (into {} (map (juxt :id :position) (:normalized state)))
          changed (count (remove #(= (:position %) (get old-positions (:id %)))
                                 (:normalized advanced)))
          metrics (assoc (:metrics advanced) :distance-sources-recomputed
                         (get-in edited [:metrics :distance-sources-recomputed])
                         :changed-positions changed)
          result (assoc advanced :revision (inc (:revision state)) :deltas deltas :metrics metrics)]
      [result [{:revision (:revision result) :deltas deltas :nodes (:nodes result)
                :metrics metrics}]])
    (catch Exception error
      [state [{:revision (:revision state)
               :error {:message (.getMessage error) :data (ex-data error)}}]])))

(defn layout-flow
  "Return an input-channel -> output-channel operator, retaining a layout session.
  Uses existing scan-emit backpressure/closure behavior. Outputs include only the
  final replacement deltas for each atomic edit+relax command."
  ([state] (layout-flow state 8))
  ([state buffer-size] (stream/scan-emit state transition buffer-size)))
