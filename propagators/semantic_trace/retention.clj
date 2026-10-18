(ns propagators.semantic-trace.retention
  "Host capability for retaining original frozen adapters. No evaluator policy."
  (:require [propagators.compiler-2.runtime.boundary :as boundary]
            [propagators.compiler-2.runtime.ids :as ids]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.compound-object :as object]
            [propagators.message :as message]
            [propagators.network-patch :as patch]
            [propagators.semantic-trace.capture :as capture]))

(def storage-key :graph.lain-editor.semantic-capture/sources)

(defn request [source captured publisher]
  (let [observation (capture/observation-id captured)
        id [::retain observation]
        receipt (ids/stable-node-id ::receipt observation)
        request (boundary/environment-effect-request
                 id ::retain {:observation observation :source source
                              :capture captured :publisher publisher} receipt)]
    [(patch/declare-cell (ids/boundary-outbox-id))
     (patch/declare-cell receipt)
     (message/message (ids/boundary-outbox-id)
                      (object/compound-object {id request}))]))

(defn handle-retention [_services session request]
  (let [{:keys [observation] :as entry} (:boundary/payload request)
        sources (or (get session storage-key)
                    (get-in session [:runtime/options storage-key])
                    (atom {}))]
    (swap! sources #(if (contains? % observation)
                     %
                     (assoc % observation (dissoc entry :observation))))
    {:state (assoc session storage-key sources)
     :receipt {:status :retained}}))

(def extension
  (extension/extension-bundle
   {:id ::retention :bindings []
    :effects [{:effect/symbol 'retain-semantic-source
               :effect/arities #{1}
               :effect/normalize identity
               :effect/identity-parts identity
               :boundary/port :environment :boundary/kind ::retain
               :effect/handler-symbol 'propagators.semantic-trace.retention/handle-retention}]}))
