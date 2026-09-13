(ns propagators.compiler-2.runtime.session.extension
  "Session capability bundles for compiler bindings and boundary handlers."
  (:require [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.operators.environment :as operators]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]))

(defprotocol SessionExtension
  (extension-id [extension])
  (extension-bindings [extension context])
  (extension-effects [extension]))

(deftype ExtensionBundle [id bindings-fn effects]
  SessionExtension
  (extension-id [_]
    id)
  (extension-bindings [_ context]
    (bindings-fn context))
  (extension-effects [_]
    effects))

(defprotocol EffectHandler
  (handle-effect [handler services session request]))

(deftype NamedHandler [handler-symbol]
  EffectHandler
  (handle-effect [_ services session request]
    (let [handler (requiring-resolve handler-symbol)]
      (cond
        (ifn? handler)
        (handler services session request)

        :else
        (throw
         (ex-info "Named effect handler is not callable"
                  {:handler-symbol handler-symbol
                   :request request}))))))

(defn extension-bundle
  [{:keys [id bindings effects]}]
  (cond
    (nil? id)
    (throw (ex-info "Session extension requires an id" {}))

    (not (ifn? bindings))
    (throw (ex-info "Session extension bindings must be callable"
                    {:extension-id id :bindings bindings}))

    (not (sequential? effects))
    (throw (ex-info "Session extension effects must be sequential"
                    {:extension-id id :effects effects}))

    :else
    (ExtensionBundle. id bindings (vec effects))))

(defn- validate-effect
  [effect]
  (cond
    (not (symbol? (:effect/symbol effect)))
    (throw (ex-info "Effect specification requires a binding symbol"
                    {:effect effect}))

    (not (set? (:effect/arities effect)))
    (throw (ex-info "Effect specification requires an arity set"
                    {:effect effect}))

    (not (ifn? (:effect/normalize effect)))
    (throw (ex-info "Effect specification requires a normalizer"
                    {:effect effect}))

    (not (ifn? (:effect/identity-parts effect)))
    (throw (ex-info "Effect specification requires an identity function"
                    {:effect effect}))

    :else
    effect))

(defn effect-registration
  [effect]
  (let [effect (validate-effect effect)
        port (:boundary/port effect)
        kind (:boundary/kind effect)
        handler-symbol (:effect/handler-symbol effect)]
    (cond
      (nil? port)
      (throw (ex-info "Effect specification requires a boundary port"
                      {:effect effect}))

      (nil? kind)
      (throw (ex-info "Effect specification requires a boundary kind"
                      {:effect effect}))

      (not (qualified-symbol? handler-symbol))
      (throw (ex-info "Effect handler must be a qualified symbol"
                      {:effect effect}))

      :else
      {:key [port kind]
       :id [port kind handler-symbol]
       :handler (NamedHandler. handler-symbol)})))

(defn- registrations
  [effects]
  (mapv effect-registration effects))

(defn- add-registration
  [registry {:keys [key id] :as proposed}]
  (let [existing (get registry key)]
    (cond
      (nil? existing)
      (assoc registry key proposed)

      (= (:id existing) id)
      registry

      :else
      (throw
       (ex-info "Session effect handler key is already registered"
                {:handler-key key
                 :existing-handler-id (:id existing)
                 :proposed-handler-id id})))))

(defn register-effects
  [registry effects]
  (reduce add-registration (or registry {}) (registrations effects)))

(defn- effect-binding
  [context effect]
  (let [outbox-id (:outbox-id context)]
    (cond
      (nil? outbox-id)
      (throw (ex-info "Effect binding requires a boundary outbox"
                      {:effect (:effect/symbol effect)}))

      :else
      [(:effect/symbol effect)
       (operators/effect-operator
        (assoc effect
               :effect/outbox-id outbox-id
               :effect/context context))])))

(defn program-bindings
  [extension context]
  (let [ordinary (extension-bindings extension context)]
    (cond
      (not (sequential? ordinary))
      (throw (ex-info "Session extension bindings must be sequential"
                      {:extension-id (extension-id extension)
                       :bindings ordinary}))

      :else
      (let [effects (mapv #(effect-binding context (validate-effect %))
                          (extension-effects extension))]
        (into (vec ordinary) effects)))))

(defn- duplicate-symbols
  [bindings]
  (->> bindings
       (map first)
       frequencies
       (keep (fn [[symbol count]]
               (when (> count 1) symbol)))
       vec))

(defn- validate-bindings
  [extension bindings]
  (let [invalid (remove #(and (vector? %) (= 2 (count %))) bindings)]
    (cond
      (seq invalid)
      (throw (ex-info "Session extension bindings must be ordered pairs"
                      {:extension-id (extension-id extension)
                       :invalid-bindings (vec invalid)}))

      :else
      (let [duplicates (duplicate-symbols bindings)]
        (cond
          (seq duplicates)
          (throw
           (ex-info "Session extension contains duplicate binding symbols"
                    {:extension-id (extension-id extension)
                     :duplicate-symbols duplicates}))

          :else
          bindings)))))

(defn- live-parent
  [network parent extension-id]
  (cond
    (ids/node-id? parent)
    {:net (nb/ensure-cell network parent)
     :env parent
     :props []}

    (map? parent)
    (let [root-id (runtime-ids/stable-node-id
                   :compiler-2 :session-extension-root extension-id)
          imported (env/import-environment-topology network root-id parent)]
      {:net (:net imported)
       :env (:env-id imported)
       :props (:prop-ids imported)})

    :else
    (throw (ex-info "Session extension requires a lexical environment"
                    {:extension-id extension-id :environment parent}))))

(defn install-extension-bindings
  [network parent-id child-id bindings]
  (let [[scope-props scoped]
        ((env/p:scope-frame parent-id child-id (set (map first bindings)))
         network)
        declared (env/declare-bindings scoped child-id child-id bindings)]
    {:net (:net declared)
     :env child-id
     :props (into (vec scope-props) (:props declared))}))

(defn- declaration-id
  [extension bindings registrations]
  [(extension-id extension)
   (mapv first bindings)
   (mapv :id registrations)])

(defn install-session-extension
  [session extension context]
  (let [bindings (validate-bindings extension (program-bindings extension context))
        effects (extension-effects extension)
        registrations (registrations effects)
        id (extension-id extension)
        declaration (declaration-id extension bindings registrations)
        existing (get-in session [:session/extensions id])]
    (cond
      (= declaration (:declaration existing))
      session

      (some? existing)
      (throw (ex-info "Session extension id is already installed"
                      {:extension-id id
                       :existing (:declaration existing)
                       :proposed declaration}))

      :else
      (let [handlers (register-effects (:environment/handlers session) effects)
            parent (live-parent (:program/net session) (:program/env session) id)
            child-id (runtime-ids/stable-node-id
                      :compiler-2 :session-extension id)
            installed (install-extension-bindings
                       (:net parent) (:env parent) child-id bindings)
            props (into (vec (:props parent)) (:props installed))
            network (nb/run-propagators (:net installed) props)
            receipt {:status :installed
                     :declaration declaration
                     :environment child-id
                     :binding-symbols (mapv first bindings)
                     :handler-ids (mapv :id registrations)}]
        (-> session
            (assoc :program/net network
                   :program/env child-id
                   :environment/handlers handlers)
            (assoc-in [:session/extensions id] receipt))))))

(def core-extension
  (extension-bundle
   {:id :compiler-2/core-session-effects
    :bindings (constantly [])
    :effects [(operators/load-primitive-environment-effect)
              (operators/load-lain-effect)
              (operators/save-environment-effect)]}))

(def client-extension
  (extension-bundle
   {:id :compiler-2/client-session-effects
    :bindings (constantly [])
    :effects [(operators/load-blocks-effect)
              (operators/save-blocks-effect)]}))

(defn default-handler-registry
  []
  (-> {}
      (register-effects (extension-effects core-extension))
      (register-effects (extension-effects client-extension))))
