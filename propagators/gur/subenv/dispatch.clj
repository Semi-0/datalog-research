(ns propagators.gur.subenv.dispatch
  "Evaluator dispatch hook for lexical sub-env refs."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.gur.subenv.env :as env]
            [propagators.gur.subenv.queue :as queue]
            [propagators.gur.subenv.scoped-slot :as scoped-slot]
            [propagators.helpers.task-queue :as tq]
            [propagators.message :refer [message message-id message-value]]
            [propagators.network :as net]))

(declare eval-cell*)

(defn- eval-child-local
  [msg child-net]
  (let [eval-cell (requiring-resolve 'propagators.core/eval-cell)
        [tasks child-net'] (eval-cell (message-id msg) msg child-net)]
    {:network child-net'
     :tasks tasks}))

(defn- eval-child-dispatch
  [msg child-net]
  (let [[tasks child-net'] (eval-cell* (net/net-dict-or-empty child-net)
                                       msg
                                       child-net)]
    {:network child-net'
     :tasks tasks}))

(defn- owner-child-network
  [parent-net owner-id]
  (some-> (net/network-lookup-cell parent-net owner-id)
          cell/cell-strongest))

(defn- invalid-owner-route!
  [owner-id child-net]
  (throw (ex-info "subenv dispatch owner does not hold a child network"
                  {:owner-id owner-id
                   :owner-value child-net})))

(defn- store-child-network
  [eval-cell parent-net owner-id child-net tasks]
  (eval-cell owner-id
             (message owner-id (queue/queue-child-props child-net tasks))
             parent-net))

(defn- route-through-owner
  [eval-cell parent-net owner-id routed-msg child-update]
  (let [child-net (owner-child-network parent-net owner-id)]
    (cond
      (value/contradiction? child-net)
      [tq/empty-queue parent-net]

      (not (net/net? child-net))
      (invalid-owner-route! owner-id child-net)

      :else
      (let [child-net* (scoped-slot/import-accessor-parent-cells
                         child-net
                         parent-net
                         (message-value routed-msg))
             {:keys [network tasks]} (child-update child-net*)]
         (store-child-network eval-cell parent-net owner-id network tasks)))))

(defn eval-cell*
  [directory msg parent-net]
  (let [eval-cell (requiring-resolve 'propagators.core/eval-cell)
        route (env/resolve-dispatch directory (message-id msg) parent-net)]
    (case (first route)
      :dispatch/local
      (let [[_ cell-id] route]
        (eval-cell cell-id (message cell-id (message-value msg)) parent-net))

      :dispatch/subenv
      (let [[_ owner-id local-id] route
            child-msg (message local-id (message-value msg))]
        (route-through-owner eval-cell
                             parent-net
                             owner-id
                             child-msg
                             #(eval-child-local child-msg %)))

      :dispatch/subenv-ref
      (let [[_ owner-id target] route
            child-msg (message target (message-value msg))]
        (route-through-owner eval-cell
                             parent-net
                             owner-id
                             child-msg
                             #(eval-child-dispatch child-msg %))))))
