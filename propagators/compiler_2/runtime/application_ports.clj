(ns propagators.compiler-2.runtime.application-ports
  "Read the primary return and ordered member ports from connected topology."
  (:require [propagators.compiler-2.runtime.returned-outputs :as outputs]
            [propagators.gur :as gur]
            [propagators.network :as net]))

(defn application-ports [network application]
  (let [id (:application-id application)
        prop-id (gur/stable-node-id [id :apply-prop])
        graph-outputs (:outputs (get (net/net-graph network) prop-id))
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
    (when-not (every? #(contains? graph-outputs %) (into [result] members))
      (throw (ex-info "Application output names require graph connections"
                      {:application-id id :return result :members members})))
    {:inputs (vec (:argument-ids application))
     :outputs (into [result] members)
     :return-id result
     :member-outputs members
     :output-status (or status :pending)
     :routing :application}))
