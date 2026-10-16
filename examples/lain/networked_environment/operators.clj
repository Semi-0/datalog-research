(ns examples.lain.networked-environment.operators
  "Only declarations and ordinary messages. No socket or filesystem operations."
  (:require [examples.lain.networked-environment.model :as model]
            [examples.lain.networked-environment.tracing :as tracing]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.operators.environment :as effects]
            [propagators.datastructures.compound-object :as obj]
            [propagators.experimental.visualization.data :as data]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.compiler-2.runtime.declaration-effects :as nb]
            [propagators.network-patch :as patch]
            [propagators.relationship-observer :as observer]))

(defn- declare-port [network arguments output direction]
  (let [name (model/payload (model/current network (first arguments)))
        cell (if (= :input direction) output (second arguments))
        existing (get (model/indexed network model/ports-scope) name)]
    (when-not (keyword? name)
      (throw (ex-info "Port name must be a concrete keyword" {:name name})))
    (when (and existing (not= cell (:cell existing)))
      (throw (ex-info "Port declared with two different cells" {:port name})))
    [(-> (nb/ensure-cell network cell)
         (net/update-net-dict-entry patch/name-bindings-key
                                   assoc-in [model/ports-scope name]
                                   {:cell cell :direction direction})
         (nb/emit-effect
          (patch/bind-name model/ports-scope name
                           {:cell cell :direction direction})))
     [] cell]))

(def input
  (operator/operator-closure
   {:name 'input
    :install (fn [network arguments output]
               (when-not (= 1 (count arguments))
                 (throw (ex-info "input expects one port name" {})))
               (declare-port network arguments output :input))}))

(def output
  (operator/operator-closure
   {:name 'output
    :output-selector (fn [arguments _] (second arguments))
    :install (fn [network arguments fallback]
               (when-not (= 2 (count arguments))
                 (throw (ex-info "output expects a port name and cell" {})))
               (declare-port network arguments fallback :output))}))

(defn connect-receipt [_services session request]
  {:state (assoc-in session [:peer/requests (:boundary/id request)]
                    (:boundary/payload request))
   :receipt {:status :requested}})

(def connect-effect
  {:effect/symbol 'peer:connect-request
   :boundary/port :environment :boundary/kind ::connect
   :effect/arities #{1}
   :effect/handler-symbol 'examples.lain.networked-environment.operators/connect-receipt
   :effect/normalize (fn [_ [endpoint]]
                       (if (string? endpoint)
                         {:status :ready :payload {:endpoint endpoint}}
                         {:status :invalid :reason :invalid-endpoint
                          :message "connect expects a TCP endpoint string" :data {}}))
   :effect/identity-parts (juxt :endpoint)})

(defn connect [outbox]
  (operator/operator-closure
   {:name 'connect
    :install
    (fn [network arguments output]
      (let [receipt (observer/stable-node-id ::receipt output)]
        [(-> network (nb/ensure-cell output) (nb/ensure-cell receipt)) [] output]))
    :activate
    (fn [network _ arguments result]
      (when-not (= 1 (count arguments))
        (throw (ex-info "connect expects one endpoint" {})))
      (let [endpoint (model/payload (model/current network (first arguments)))
            receipt (observer/stable-node-id ::receipt result)]
        (cond
          (value/unusable? endpoint) []
          (not (string? endpoint))
          (throw (ex-info "connect expects a TCP endpoint string" {:endpoint endpoint}))
          :else
          [(patch/bind-name model/connections-scope endpoint result)
           (message/message result (obj/compound-object {:peer/address endpoint}))
           (effects/effect-request-message outbox [::connect result endpoint]
                                           ::connect {:endpoint endpoint :proxy result}
                                           receipt)])))}))

(defn query [name arity compute]
  (operator/propagator-operator
   {:name name
    :activate
    (fn [network arguments [out] _]
      (when-not (= arity (count arguments))
        (throw (ex-info "Invalid peer observation arity" {:operator name})))
      (let [contents (mapv #(net/network-cell-content network %) arguments)
            values (mapv #(model/payload (model/current network %)) arguments)]
        (if (some value/unusable? values)
          (data/state-messages out contents)
          (let [result (try (apply compute values)
                            (catch Exception error
                              (value/contradiction-with-provenance
                               #{{:operation name :reason (ex-message error)}})))]
            [(message/message out (data/supported [name out] result #{} contents))]))))}))

(def queries
  [['ref (query 'ref 2 tracing/reference)]
   ['inputs (query 'inputs 1 #(tracing/interfaces % :inputs))]
   ['outputs (query 'outputs 1 #(tracing/interfaces % :outputs))]
   ['trace-dependents (query 'trace-dependents 1 #(tracing/trace-reference % :upstream))]
   ['trace-downstream (query 'trace-downstream 1 #(tracing/trace-reference % :downstream))]
   ['trace-children (query 'trace-children 1 tracing/child-graph)]])
