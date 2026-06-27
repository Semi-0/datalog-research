(ns propagators.gur.accumulating.runner
  "Public runner constructor for accumulated GUR network values."
  (:require [propagators.gur.accumulating.runner.executor :as executor]
            [propagators.propagator :as prop]))

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
      (fn [_inputs _outputs parent-net]
        (let [token (executor/runner-input-token parent-net inputs)]
          (if (executor/same-input-token? token @(:last-input-token state))
            []
            (do
              (reset! (:last-input-token state) token)
              (let [boundary-ids (executor/cached-boundary-cell-ids
                                  state
                                  parent-net
                                  import-ids
                                  external-output-ids)]
                (executor/run-accumulated-messages state
                                                   parent-net
                                                   applied-net-id
                                                   import-ids
                                                   external-output-ids
                                                   boundary-ids))))))
      inputs
      (into [applied-net-id] (distinct (concat import-ids
                                               external-output-ids)))))))
