(ns propagators.compiler-2.runtime.application-ports
  "Read declared call roles and ordered returned members from connected topology."
  (:require [propagators.compiler-2.runtime.returned-outputs :as outputs]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.model.operator-value :as operator]
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
        result (:result-id application)
        ;; Observation can run while an application declaration is still partial.
        callable (when-let [operator-id (:operator-id application)]
                   (application/callable-declaration
                    (net/network-cell-strongest network operator-id)))
        interface (if (operator/operator-closure? callable)
                    (operator/operator-call callable (:argument-ids application)
                                            result (:context-id application))
                    {:inputs (vec (:argument-ids application)) :outputs [result]})
        declared-outputs (vec (distinct (concat (:outputs interface) members)))]
    (when-not (every? #(contains? graph-outputs %) declared-outputs)
      (throw (ex-info "Application output names require graph connections"
                      {:application-id id :return result :members members
                       :outputs declared-outputs :connected-outputs graph-outputs})))
    {:inputs (:inputs interface)
     :outputs declared-outputs
     :return-id result
     :member-outputs members
     :output-status (or status :pending)
     :routing :application}))
