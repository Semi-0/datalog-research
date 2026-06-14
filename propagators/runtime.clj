(ns propagators.runtime
  "Activation-local access to the evaluator continuation.")

(def ^:dynamic *continue* nil)

(defn continue
  [network]
  (if *continue*
    (*continue* network)
    (throw (ex-info "no evaluator continuation is bound" {}))))
