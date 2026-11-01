(ns examples.lain.subagents.socket-extension
  "Pure user-command and feedback policies over session-scoped fact logs."
  (:require [clojure.string :as str]
            [examples.lain.subagents.effect-boundary :as effects]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.primitives :as primitives]
            [graph.json :as json]
            [propagators.compiler-2.runtime.session.extension :as extension]))

(def command-kinds
  #{:assignment/submitted :assignment/reordered :assignment/cancel-requested})

(def envelope-keys #{:session :id :order :kind})

(defn- nonempty-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- require-session [session-id log]
  (when-not (and (facts/log? log) (= session-id (facts/session-id log)))
    (throw (ex-info "Socket policy received a foreign or invalid fact log"
                    {:expected-session session-id})))
  log)

(defn- command-payload [event]
  (if (= :socket/command (:kind event))
    (:command event)
    (apply dissoc event envelope-keys)))

(defn- command-kind [event]
  (if (= :socket/command (:kind event))
    (let [kind (:command-kind event)]
      (some #(when (= (subs (str %) 1) kind) %) command-kinds))
    (when (command-kinds (:kind event)) (:kind event))))

(defn- command-error [kind payload]
  (cond
    (nil? kind) :unsupported-command
    (not (map? payload)) :invalid-command-payload
    (= kind :assignment/submitted)
    (cond
      (not= #{:assignment-id :task} (set (keys payload))) :invalid-command-fields
      (not (nonempty-string? (:assignment-id payload))) :invalid-assignment-id
      (not (nonempty-string? (:task payload))) :invalid-task
      (> (count (:task payload)) 8192) :task-too-large
      :else nil)
    (= kind :assignment/reordered)
    (cond
      (not= #{:assignment-ids} (set (keys payload))) :invalid-command-fields
      (not (and (vector? (:assignment-ids payload))
                (<= (count (:assignment-ids payload)) 256)
                (every? nonempty-string? (:assignment-ids payload))
                (= (count (:assignment-ids payload))
                   (count (distinct (:assignment-ids payload)))))) :invalid-assignment-order
      :else nil)
    (= kind :assignment/cancel-requested)
    (cond
      (not= #{:assignment-id} (set (keys payload))) :invalid-command-fields
      (not (nonempty-string? (:assignment-id payload))) :invalid-assignment-id
      :else nil)
    :else :unsupported-command))

(defn normalize-commands
  "Validate command shape; assignment-state conflicts belong to evaluation policy."
  [session-id log]
  (require-session session-id log)
  (facts/from-facts
   session-id
   (for [event (facts/ordered-facts log)]
     (if (= :socket/command-rejected (:kind event))
       event
       (let [kind (command-kind event)
             payload (command-payload event)]
         (if-let [reason (command-error kind payload)]
           (facts/fact session-id (:id event) (:order event)
                       :socket/command-rejected
                       {:command-id (:id event)
                        :requested-kind (or (:command-kind event) (str (:kind event)))
                        :failure {:category :domain :reason reason}})
           (facts/fact session-id (:id event) (:order event) kind payload)))))))

(defn derive-assignments [session-id commands]
  (require-session session-id commands)
  (facts/select-kinds commands command-kinds))

(defn derive-controls [session-id commands]
  (require-session session-id commands)
  (facts/select-kinds commands #{:assignment/cancel-requested}))

(defn describe-commands [session-id commands]
  (require-session session-id commands)
  (facts/from-facts
   session-id
   (for [event (facts/ordered-facts commands)
         :when (or (command-kinds (:kind event))
                   (= :socket/command-rejected (:kind event)))]
     (let [rejected? (= :socket/command-rejected (:kind event))]
       (facts/fact session-id (str "command-feedback/" (:id event)) (:order event)
                   (if rejected? :user/command-rejected :user/command-accepted)
                   (cond-> {:command-id (:id event)
                            :status (if rejected? :rejected :accepted)}
                     rejected? (assoc :failure (:failure event))
                     (not rejected?) (assoc :command-kind (:kind event))))))))

(defn describe-assignments [session-id assignment-log]
  (require-session session-id assignment-log)
  (facts/from-facts
   session-id
   (for [event (facts/ordered-facts assignment-log)
         :when (= "assignment" (namespace (:kind event)))]
     (facts/fact session-id (str "assignment-feedback/" (:id event)) (:order event)
                 :user/assignment-feedback
                 {:source-kind (:kind event)
                  :details (apply dissoc event envelope-keys)}))))

(defn- bounded-value [value depth]
  (when (> depth 8)
    (throw (ex-info "Socket feedback exceeds nesting limit" {:limit 8})))
  (cond
    (and (keyword? value) (contains? #{:bool4/nothing :bool4/contradiction} value))
    (throw (ex-info "Socket feedback field is unresolved or contradictory" {}))
    (or (nil? value) (boolean? value) (keyword? value)) value
    (number? value)
    (if (or (and (instance? Double value) (not (Double/isFinite value)))
            (and (instance? Float value) (not (Float/isFinite value))))
      (throw (ex-info "Socket feedback contains a non-finite number" {}))
      value)
    (string? value)
    (if (<= (count value) 8192)
      value
      (throw (ex-info "Socket feedback string exceeds limit" {:limit 8192})))
    (record? value)
    (throw (ex-info "Socket feedback cannot serialize a domain object or network" {}))
    (map? value)
    (do
      (when (> (count value) 64)
        (throw (ex-info "Socket feedback object exceeds field limit" {:limit 64})))
      (into {} (map (fn [[key item]]
                      (when-not (and (or (keyword? key) (string? key))
                                     (<= (count (str key)) 256))
                        (throw (ex-info "Socket feedback requires bounded named fields" {})))
                      [key (bounded-value item (inc depth))])) value))
    (vector? value)
    (do
      (when (> (count value) 256)
        (throw (ex-info "Socket feedback vector exceeds item limit" {:limit 256})))
      (mapv #(bounded-value % (inc depth)) value))
    :else
    (throw (ex-info "Socket feedback contains an unsupported unresolved value"
                    {:value-type (str (type value))}))))

(defn encode-feedback
  "Project one bounded resolved event per outbound request; do not unwrap actors."
  [session-id feedback]
  (require-session session-id feedback)
  (facts/from-facts
   session-id
   (for [event (facts/ordered-facts feedback)]
     (let [payload (bounded-value event 0)
           encoded (json/write-json payload)]
       (when (> (alength (.getBytes ^String encoded java.nio.charset.StandardCharsets/UTF_8))
                32768)
         (throw (ex-info "Socket feedback exceeds frame limit" {:limit 32768})))
       (facts/fact session-id (str "socket-write/" (:id event)) (:order event)
                   :socket/write {:payload payload})))))

(defn- unary-policy [name session-id f]
  (primitives/leaf-operator
   name {:arity 1 :inputs [0] :input-kinds [:facts]
         :output nil :session session-id :kind :facts}
   #(f session-id %)))

(defn current-write-intents
  "Current bounded transport requests for one feedback state. Retracted with the
  feedback they describe, so nothing stale can be sent twice."
  [session-id feedback]
  (mapv #(select-keys % [:session :id :order :kind :payload])
        (facts/ordered-facts (encode-feedback session-id feedback))))

(def socket-write-kind
  "Native boundary kind of this component's socket effect; effect identity is
  [kind intent-id], the runtime's own identity scheme."
  :harness/socket-write)

(defn deliver-intent
  "Session-owned socket delivery handler: one durable claim per write intent.
  Declaring the effect never sends anything: without pump ownership the request
  is deferred untouched."
  [services state request]
  (if-not (primitives/transport-armed?)
    (primitives/deferred-receipt state request)
    (let [intent (:boundary/payload request)]
      (when-not (= [socket-write-kind (:id intent)] (:boundary/id request))
        (throw (ex-info "Socket effect identity does not match its intent"
                        {:effect-id (:boundary/id request)})))
      (let [response (effects/dispatch! (:writes services) intent (:order services))]
        {:state state
         :receipt {:status :delivered :observations response}}))))

(def socket-write-effect
  {:effect/symbol 'socket:declare-write
   :boundary/port :harness
   :boundary/kind socket-write-kind
   :effect/arities #{1}
   :effect/handler-symbol 'examples.lain.subagents.socket-extension/deliver-intent
   :effect/identity-parts (comp vector :id)
   :effect/normalize (fn [_ [intents]]
                       (cond
                         (nil? intents) {:status :wait}
                         (not (every? map? intents))
                         {:status :invalid :reason :invalid-intents
                          :message "socket:declare-write needs bounded intent maps"
                          :data {:intents (count intents)}}
                         :else {:status :ready :payload (vec intents)}))})

(defn session-extension [session-id]
  (facts/empty-log session-id)
  (extension/extension-bundle
   {:id ::policies
    :bindings (conj
               (vec
                [['socket:normalize-commands (unary-policy ::normalize session-id normalize-commands)]
                 ['user:derive-assignments (unary-policy ::assignments session-id derive-assignments)]
                 ['user:derive-controls (unary-policy ::controls session-id derive-controls)]
                 ['user:describe-commands (unary-policy ::command-feedback session-id describe-commands)]
                 ['user:describe-assignments (unary-policy ::assignment-feedback session-id describe-assignments)]
                 ['socket:encode-feedback (unary-policy ::encode session-id encode-feedback)]])
               ['socket:current-writes
                (primitives/leaf-operator 'socket:current-writes
                 {:arity 1 :inputs [0] :input-kinds [:facts]
                  :output nil :session session-id :kind :value}
                 (partial current-write-intents session-id))]
               ['socket:emit-writes (primitives/intent-effect-operator socket-write-effect)])
    :effects [socket-write-effect]}))
