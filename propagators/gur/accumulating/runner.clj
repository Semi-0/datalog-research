(ns propagators.gur.accumulating.runner
  "Public runner constructor for accumulated GUR network values."
  (:require [propagators.gur.accumulating.runner.executor :as executor]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def runner-prop-name :gur/run-accumulated-network)

(defn runner-prop-ids
  [network]
  (->> (net/net-env network)
       (keep (fn [[id entry]]
               (cond
                 (and (prop/prop? entry)
                      (= runner-prop-name (prop/prop-name entry)))
                 id

                 :else
                 nil)))
       vec))

(defn- runner-state
  []
  {:task-cursor (atom {})
   :request-cache (atom #{})
   :request-scan-cache (atom nil)
   :prop-state-cache (atom {})
   :prop-io-cache (atom {})
   ;; ponytail: runner-local scheduling token; not recursive semantics.
   :mailbox-epoch (atom 0)
   :last-input-token (atom nil)
   :boundary-cache (atom nil)})

(defn p:run-accumulated-network
  "Install the primitive that owns execution of one accumulated recursive net.

  Recursive semantics remain declaration-only: task facts are stored in the
  accumulated network value, while the primitive keeps runtime cursors in local
  atoms and emits messages with the refined child network/output cells."
  ([applied-net-id external-output-ids]
   (p:run-accumulated-network applied-net-id [] external-output-ids))
  ([applied-net-id import-ids external-output-ids]
   (let [import-ids (vec import-ids)
         external-output-ids (vec external-output-ids)
         inputs (vec (distinct (concat [applied-net-id]
                                       import-ids
                                       external-output-ids)))
         state (runner-state)]
     (prop/construct-propagator
      runner-prop-name
      (fn [_inputs _outputs parent-net]
        (let [boundary-ids (executor/cached-boundary-cell-ids
                            state
                            parent-net
                            applied-net-id
                            import-ids
                            external-output-ids)
              projected-output-ids
              (executor/projected-boundary-output-ids parent-net
                                                      applied-net-id)
              boundary-dict-keys
              (executor/boundary-dict-keys parent-net applied-net-id)
              token {:cells (executor/runner-input-token
                             parent-net
                             (distinct (concat inputs boundary-ids)))
                     :dict (mapv (fn [dict-key]
                                   [dict-key
                                    (get (net/net-dict-or-empty parent-net)
                                         dict-key)])
                                 boundary-dict-keys)}]
          (cond
            (= token @(:last-input-token state))
            []

            :else
            (do
              (reset! (:last-input-token state) token)
              (executor/run-accumulated-messages state
                                                 parent-net
                                                 applied-net-id
                                                 import-ids
                                                 external-output-ids
                                                 boundary-ids
                                                 projected-output-ids
                                                 boundary-dict-keys)))))
      inputs
      (into [applied-net-id] (distinct (concat import-ids
                                               external-output-ids)))))))
