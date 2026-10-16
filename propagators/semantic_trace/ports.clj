(ns propagators.semantic-trace.ports
  "Represent a returned input at its caller boundary, retaining runtime aliases."
  (:require [propagators.dataflow-projection :as normalization]
            [propagators.semantic-trace.logic :as logic]))

(defn explain [applications copies edges]
  (let [outgoing (group-by first (set edges))
        evidence {:member-output (set (mapcat (fn [{:keys [id outputs result]}]
                                               (for [port outputs :when (not= port result)] [id port]))
                                             applications))
                  :argument (set (mapcat (fn [{:keys [id inputs]}]
                                          (map #(vector id %) inputs)) applications))
                  :return-copy (set (for [{:keys [kind app source id target]} copies
                                         :when (= :return kind)] [app source id target]))
                  :sole-consumer (set (for [[source routes] outgoing :when (= 1 (count routes))]
                                        [source (second (first routes))]))}
        derived (logic/run evidence logic/inout-port-rules)
        aliases (into {} (map (fn [[port caller _copy]] [port caller])) (:port-alias derived))
        hidden (set (map #(nth % 2) (:port-alias derived)))]
    {:edb evidence :derived derived :aliases aliases
     :edges (normalization/rename-edges aliases (remove #(some hidden %) edges))}))
