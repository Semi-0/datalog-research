(ns examples.lain.networked-environment.session
  "Standard session assembly. All process identity/premise data is explicit."
  (:require [examples.lain.networked-environment.codec :as codec]
            [examples.lain.networked-environment.model :as model]
            [examples.lain.networked-environment.operators :as operators]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.env.index :as index]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb])
  (:import [java.util UUID]))

(defn process-identity []
  (let [source (ids/new-node-id)]
    {:id (UUID/randomUUID) :premise source
     :premise-value (model/next-source source value/nothing true :active)}))

(defn bundle [identity generation]
  (extension/extension-bundle
   {:id [::peer (:id identity)]
    :bindings (into
               [['runtime-env {:peer/environment true :peer/id (:id identity)
                               :peer/generation generation}]
                ['process-premise (env/cell-binding (:premise identity))]
                ['input operators/input]
                ['output operators/output]
                ['connect (operators/connect (runtime-ids/boundary-outbox-id))]]
               operators/queries)
    :effects [operators/connect-effect]}))

(defn primitive-registry [state]
  (let [network (:program/net state)
        environment (:program/env state)
        symbols (set (vals (index/binding-names network)))
        bindings (keep (fn [sym]
                         (when-let [id (env/resolve-binding-id network environment sym)]
                           (let [v (model/payload (model/current network id))]
                             (when (operator/operator-closure? v)
                               [(keyword "lain.primitive" (str sym)) v])))) symbols)]
    (codec/registry bindings)))

(defn prepare
  "Build an isolated candidate. Never open a socket while compiling it."
  [identity text]
  (let [generation (UUID/randomUUID)
        session (runtime/new-session)
        declared (bundle identity generation)
        context {:outbox-id (runtime-ids/boundary-outbox-id)}]
    (runtime/install-session-extension! session ttms/session-extension context)
    (swap! session update :program/net nb/install-cell
           (:premise identity) (:premise-value identity) (:premise-value identity))
    (runtime/install-session-extension! session declared context)
    (let [registry (primitive-registry @session)]
      (loader/load-source! session text)
      (swap! session update :program/net net/assoc-net-dict-entry model/identity-key
             {:id (:id identity) :generation generation :premise (:premise identity)})
      {:session session :identity identity :generation generation
       :registry registry :source text :extension declared})))

(defn context [prepared]
  (let [state @(:session prepared)]
    {:peer/environment true :peer/id (get-in prepared [:identity :id])
     :peer/generation (:generation prepared)
     :peer/env (:program/env state) :peer/net (:program/net state)}))

(defn ports [prepared]
  (model/indexed (:program/net @(:session prepared)) model/ports-scope))

(defn port-value [prepared port]
  (if-let [id (get-in (ports prepared) [port :cell])]
    (model/current (:program/net @(:session prepared)) id)
    (throw (ex-info "Unknown local port" {:port port}))))

(defn binding-value [prepared name]
  (let [state @(:session prepared)
        id (env/resolve-binding-id (:program/net state) (:program/env state) name)]
    (if id (model/current (:program/net state) id) value/nothing)))
