(ns propagators.compiler-2.runtime.application-ports
  "Read the primary return and positional member ports from named topology."
  (:require [propagators.compiler-2.runtime.returned-outputs :as outputs]
            [propagators.gur :as gur]
            [propagators.network :as net]))

(defn application-ports [network application]
  (let [id (:application-id application)
        members (->> (outputs/outputs network)
                     (filter (fn [[[owner _position] _]] (= id owner)))
                     (sort-by (comp second key))
                     (mapv val))
        status-id (get-in (net/network-dict-entry network gur/name-bindings-key)
                          [outputs/status-scope id])
        status (if status-id
                 (:status (net/network-cell-strongest network status-id))
                 :pending)
        result (:result-id application)]
    {:inputs (vec (:argument-ids application))
     :outputs (into [result] members)
     :return-id result
     :member-outputs members
     :output-status (or status :pending)
     :routing :application}))
