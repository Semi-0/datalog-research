(ns examples.lain.subagents.primitives
  "Leaf policy operators and a session-scoped fact union runner. Actor graphs
  remain connected; only explicitly typed fact leaves participate in union."
  (:require [examples.lain.subagents.facts :as facts]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.operators.environment :as effect-ops]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.support-collection :as collection]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur.flat :as gur]
            [propagators.helpers.task-queue :as queue]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.runner-constructor :as constructor]
            [propagators.stdlib.observation :as observation]))

(defn- checked-log [session log]
  (when-not (and (facts/log? log) (= session (facts/session-id log)))
    (throw (ex-info "Expected facts owned by this session" {:session session :type (type log)})))
  log)

(defn- leaf-value [session kind network id]
  (let [candidate (data/payload (net/network-cell-strongest network id))]
    (when (value/contradiction? candidate)
      (throw (ex-info "Contradictory policy input" {:cell id})))
    (case kind
      :facts (if (value/nothing? candidate) (facts/empty-log session) (checked-log session candidate))
      :value (if (value/nothing? candidate) nil candidate)
      (throw (ex-info "Unsupported policy input kind" {:kind kind})))))

(defn- apply-fact-patch [session emitter candidate network]
  (if-not (message/message? candidate)
    (runner/apply-patch emitter candidate network)
    (let [id (message/message-id candidate)
          update (data/payload (message/message-value candidate))
          previous (data/payload (net/network-cell-strongest network id))]
      (cond
        (facts/log? update)
        (let [update (checked-log session update)
              merged (cond
                       (value/nothing? previous) update
                       (facts/log? previous) (facts/merge-logs (checked-log session previous) update)
                       :else (throw (ex-info "Fact port contains incompatible information"
                                             {:cell id :existing-type (str (type previous))})))
              entry (net/network-lookup-cell network id)]
          (if (= previous merged)
            [queue/empty-queue network]
            [(queue/into-queue (nb/neighbor-propagator-ids network id))
             (net/assoc-net-cell network id (cell/cell (cell/cell-name entry) merged merged))]))

        (and (facts/log? previous) (value/nothing? update))
        [queue/empty-queue network]

        :else (runner/apply-patch emitter candidate network)))))

(defn- evaluate-fact-patches [session evaluated network {:keys [success fail]}]
  (try
    (let [{:keys [messages effects]} (patch/normalize-activation-return (:activation-result evaluated))
          emitter (:emitter evaluated)]
      (loop [remaining (seq (concat effects messages)) current network tasks queue/empty-queue]
        (if-let [candidate (first remaining)]
          (let [[next-tasks updated] (apply-fact-patch session emitter candidate current)]
            (recur (next remaining) updated (queue/merge-queues tasks next-tasks)))
          (success tasks current))))
    (catch Throwable error (fail error))))

(defn runner-transform
  "Use only in the owning session's runner composition. This specializes the
  ordinary FIFO iterator's patch evaluator; native patches retain their handlers.
  Compose observation/history transforms outside this domain iterator. No global
  merge method or process-wide mutable table is installed."
  [session]
  (facts/empty-log session)
  (fn [_ordinary-iterator]
    (constructor/network-iterator-constructor
     runner/fifo-task-policy runner/evaluate-propagator
     (partial evaluate-fact-patches session))))

(defn publish-facts!
  "Publish monotone facts through the owning session's domain input boundary.
  Core scalar publication cannot union these leaves before the runner starts."
  [session-atom session-id cell-id log]
  (checked-log session-id log)
  (locking session-atom
    (let [network (:program/net @session-atom)
          [tasks updated] (apply-fact-patch session-id nil (message/message cell-id log) network)
          settled (binding [runner/*advance-transform*
                            (runner/compose-advance-transforms
                             runner/*advance-transform* (runner-transform session-id))]
                    (runner/completed-network (runner/run-network tasks updated)))]
      (swap! session-atom assoc :program/net settled)
      session-atom)))

(def ^{:dynamic true, :private true} *transport-ownership*
  "Truthy only while the owning session's delivery pump performs delivery on this
  thread. Handlers consult `transport-armed?`: declaring an effect, compiling a
  session or observing facts never performs external transport."
  nil)

(defn transport-armed?
  "True exactly while `with-transport-ownership` is in effect on this thread."
  []
  (boolean *transport-ownership*))

(defn with-transport-ownership
  "Run f with delivery ownership claimed by the calling pump."
  [f]
  (when-not (fn? f)
    (throw (ex-info "Transport ownership needs an explicit thunk" {})))
  (binding [*transport-ownership* ::delivery-pump] (f)))

(defn deferred-receipt
  "Native handler result when the pump does not own delivery on this thread: the
  effect is declared, not performed, and stays current for the pump to claim."
  [state request]
  {:state state
   :receipt {:status :deferred
             :reason :pump-does-not-own-delivery
             :effect-id (:boundary/id request)}})

(defn current-leaf-value
  "Read one current leaf value the way this domain always has: the evidence-carrying
  cell content projected to its value. Native effect operators must use this and
  never the raw cell content, whose wrapper is evidence, not the intent."
  [network id]
  (let [raw (net/network-cell-strongest network id)
        payload (if (value/unusable? raw) raw (data/payload raw))]
    (if (value/unusable? payload) value/nothing payload)))

(defn intent-effect-operator
  "Declare one NATIVE boundary effect per distinct current intent.

  The effect specification is the session extension's own registration (port,
  kind, identity-parts); this operator only spreads each current intent into its
  own stable slot of the session boundary outbox. Identity comes from
  :effect/identity-parts, so every intent owns a distinct stable effect slot and
  a distinct stable receipt slot key. Nothing is collapsed into one last-value
  cell and no whole domain object is ever materialized. Intents that are not
  current produce no request, so an ineligible/canceled intent emits nothing."
  [{:effect/keys [symbol normalize identity-parts] :as effect}]
  (let [kind (:boundary/kind effect)
        port (:boundary/port effect)
        outbox-id (runtime-ids/boundary-outbox-id)]
    (operator/operator-closure
     {:name symbol
      :output-selector (fn [_arg-ids fallback-id] fallback-id)
      :activate
      (fn [network _context arg-ids receipt-id]
        (let [values (mapv #(current-leaf-value network %) arg-ids)
              invocation (effect-ops/normalize-effect-invocation effect {} values)]
          (case (:status invocation)
            :wait
            []

            :invalid
            [(effect-ops/effect-failure-message receipt-id [kind :invalid arg-ids]
                                                kind invocation)]

            :ready
            (if-let [slots (seq (for [intent (:payload invocation)
                                      :let [effect-id (into [kind] (identity-parts intent))]]
                                  [(runtime-ids/effect-slot-key effect-id)
                                   {:boundary/effect true
                                    :boundary/id effect-id
                                    :boundary/port port
                                    :boundary/kind kind
                                    :boundary/payload intent
                                    :boundary/receipt-id receipt-id
                                    :boundary/receipt-slot
                                    (runtime-ids/receipt-slot-key effect-id)}]))]
              ;; Every slot is independent: concurrent intents coexist instead of
              ;; overwriting one shared request cell.
              [(message/message outbox-id (obj/compound-object (into {} slots)))]
              []))))})))


(defn- selected-inputs [spec arguments]
  (when-not (= (:arity spec) (count arguments))
    (throw (ex-info "Policy arity mismatch" {:expected (:arity spec) :actual (count arguments)})))
  (mapv #(nth arguments %) (:inputs spec)))

(defn- calculate [spec f network inputs]
  (apply f (mapv #(leaf-value (:session spec) %1 network %2) (:input-kinds spec) inputs)))

(defn- input-watch-facts [session network kinds inputs]
  (facts/from-facts
   session
   (mapcat (fn [[index [input-kind input]]]
             (when (= :facts input-kind)
               (map (fn [event]
                      (facts/fact session (str "policy/watch/" (pr-str [index (:id event)]))
                                  (:order event) :policy/input
                                  {:input index :fact event}))
                    (facts/ordered-facts (leaf-value session :facts network input)))))
           (map vector (range) (map vector kinds inputs)))))

(defn- install-value [name spec f network arguments fallback]
  (let [inputs (selected-inputs spec arguments)
        output (if (some? (:output spec)) (nth arguments (:output spec)) fallback)
        value-inputs (mapv #(nth inputs %)
                           (keep-indexed (fn [i kind] (when (= :value kind) i))
                                         (:input-kinds spec)))
        watch (gur/stable-node-id [::watch output])
        source (gur/stable-node-id [::source output])
        ;; A dedicated injection cell for the CURRENT values of the :value inputs.
        ;; Like `source` it is written only through source stamping, so an actual
        ;; value transition advances its epoch and no unchanged republication
        ;; produces any event. It exists purely as reactive watch evidence.
        value-watch (when (seq value-inputs) (gur/stable-node-id [::value-watch output]))
        prepared (reduce nb/ensure-cell network
                         (cond-> [watch source output]
                           value-watch (conj value-watch)))
        sample (fn [current]
                 (let [result (calculate spec f current inputs)]
                   (if (nil? result) value/nothing result)))]
    (let [[watch-id watched]
          ((prop/construct-propagator
            [::watch name]
            (fn [_ _ current]
              [(message/message watch
                 (input-watch-facts (:session spec) current (:input-kinds spec) inputs))])
            inputs [watch]) prepared)
          ;; Fact evidence alone cannot notice a pure VALUE transition: when an
          ;; offer becomes current (or current again) without any new fact, the
          ;; fact-watch log is identical and the sampler would never re-run.
          ;; Publish one stamped value snapshot per activation into its own
          ;; epoch cell; a changed value changes that cell and re-triggers the
          ;; sampler, which re-samples the whole current Net as always. This is
          ;; the leaf's own reactive watch, not host polling or pump-side work.
          [[value-watch-id value-recheck-id] value-stepped]
          (if-not value-watch
            [[nil nil] watched]
            (let [[vw-id built]
                  ((prop/construct-propagator
                    [::value-watch name]
                    (fn [_ _ current]
                      [(message/message value-watch
                                        (mapv #(current-leaf-value current %) value-inputs))])
                    inputs [value-watch]) watched)
                  vw-prop (net/network-env-lookup built vw-id)
                  vw-activate (prop/compose-activation
                               (prop/prop-f vw-prop)
                               (publication/stamp-source value-watch :active)
                               (message/lift-message collection/content))
                  [recheck-id rechecked]
                  ((publication/p:observe
                    (gur/stable-node-id [::value-recheck output]) value-watch source sample)
                   (net/assoc-net-prop built vw-id (assoc vw-prop :activate vw-activate)))]
              [[vw-id recheck-id] rechecked]))
          [publication-id published]
          ((publication/p:observe
            (gur/stable-node-id [::publish output]) watch source sample) value-stepped)
          [forward-id installed] ((observation/identity [::value name] source output) published)]
      [installed (cond-> [watch-id publication-id forward-id]
                   value-watch (into [value-watch-id value-recheck-id])) output])))

(defn leaf-operator
  "Lift a pure policy over declared leaf arguments. Fact inputs have an explicit
  empty identity. Current-value output is TTMS published and may withdraw via nil."
  [name {:keys [arity inputs input-kinds session kind] :or {kind :facts} :as spec} f]
  (facts/empty-log session)
  (when-not (and (pos-int? arity) (vector? inputs) (vector? input-kinds)
                 (= (count inputs) (count input-kinds))
                 (every? #(and (integer? %) (<= 0 %) (< % arity)) inputs)
                 (every? #{:facts :value} input-kinds) (ifn? f)
                 (contains? #{:facts :value} kind))
    (throw (ex-info "Invalid leaf policy declaration" {:name name})))
  (let [spec (assoc spec :kind kind)]
    (case kind
      :value
      (do
        (when-not (every? #{:facts :value} input-kinds)
          (throw (ex-info "Current-value policy watches bounded leaf inputs only" {:name name})))
        (operator/operator-closure {:name name :install (partial install-value name spec f)}))
      :facts
      (operator/propagator-operator
       {:name name
        :input-selector (fn [arguments _fallback _context] (selected-inputs spec arguments))
        :output-selector (fn [arguments fallback]
                           (if (some? (:output spec)) (nth arguments (:output spec)) fallback))
        :messages (fn [network {:keys [inputs outputs]}]
                    (let [result (checked-log session (calculate spec f network inputs))]
                      [(message/message (first outputs) result)]))}))))
