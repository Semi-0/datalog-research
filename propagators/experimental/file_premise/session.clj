(ns propagators.experimental.file-premise.session
  "Lain composition declarations and an explicit session effect for execution."
  (:require [clojure.string :as str]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.file-premise.composition :as composition]
            [propagators.experimental.file-premise.files :as files]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(def load-operator
  (operator/operator-closure
   {:name 'file-load
    :activate
    (fn [network _context arg-ids output]
      (let [values (mapv #(net/network-cell-strongest network %) arg-ids)]
        (cond
          (not= 3 (count values))
          (throw (ex-info "file-load expects composition, path, alias" {:arity (count values)}))

          (some value/unusable? values) []

          :else
          [(message output (apply composition/load-declaration values))])))}))

(defn- normalize-run [_context [plan query-file revision]]
  (let [revision (if (nil? revision) 0 revision)]
    (cond
    (not (composition/composition? plan))
    {:status :invalid :reason :invalid-composition
     :message "file-run expects a file composition"}

    (or (not (string? query-file)) (str/blank? query-file))
    {:status :invalid :reason :invalid-query :message "file-run expects a query file path"}

    (not (nat-int? revision))
    {:status :invalid :reason :invalid-revision :message "file-run revision must be nonnegative"}

    :else
    {:status :ready
     :payload {:modules (:modules plan) :query-file query-file :revision revision}})))

(defn- answer-receipt [world]
  (let [answer (net/network-cell-strongest (:net world) (get-in world [:compiled :cell]))
        base (observation/base answer)
        ready? (not (value/unusable? base))]
    {:status :completed :ready? ready?
     :answer (if ready? base nil)
     :support (datum/support-of answer)}))

(defn run-composition
  "Boundary handler: prepare a private world, then install it only on success.
  The session boundary owns request deduplication and failed receipts."
  [_services session request]
  (let [{:keys [modules query-file]} (:boundary/payload request)
        loaded (reduce (fn [world {:keys [path alias]}] (files/load-file world path alias))
                       (files/initial-world) modules)
        world (files/query loaded (slurp query-file))
        receipt (answer-receipt world)]
    {:state (assoc-in session [::worlds (:boundary/id request)] world)
     :receipt receipt}))

(def session-extension
  (extension/extension-bundle
   {:id ::file-compositions
    :bindings [['file-composition composition/empty-composition]
               ['file-load load-operator]]
    :effects [{:effect/symbol 'file-run
               :boundary/port :environment
               :boundary/kind ::run
               :effect/arities #{2 3}
               :effect/normalize normalize-run
               :effect/identity-parts (juxt :modules :query-file :revision)
               :effect/handler-symbol
               'propagators.experimental.file-premise.session/run-composition}]}))

(defn install
  "Install into an existing session; ordinary session APIs execute the effect."
  [session]
  (extension/install-session-extension
   (update session :program/net nb/ensure-cell (state/boundary-outbox-id))
   session-extension
   {:outbox-id (state/boundary-outbox-id)}))
