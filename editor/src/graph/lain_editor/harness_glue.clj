(ns graph.lain-editor.harness-glue
  "Candidate-side glue: give an EDITOR-OWNED session the harness session-extension.
  The editor API `extensions` field carries Lain source strings only
  (graph.lain-editor.runtime.session/load-checked! is Lain-source-only) and
  ensure-session-state! installs nothing, so the only public function that receives
  the editor session atom before any Lain source is compiled is load-checked!.
  This glue wraps it once, per process, and installs the socket+harbor+pi policy
  bundle with the same context examples.lain.networked-environment/session uses.
  Observation posture only: no pi-transport/open!, no socket-transport/start!, no
  effect-boundary/open!, so no child, container, model, socket or network is opened.

  The second half of this namespace is the MAIN-session observer boundary: the editor
  attaches to, watches and detaches from a session it does NOT own. Attach subscribes and
  detach unsubscribes only; neither path may construct, dispose, pump or clock a session."
  (:require [clojure.java.io :as io]
            [examples.lain.subagents.harness-session :as harness]
            [examples.lain.subagents.primitives :as primitives]
            [graph.lain-editor.protected-server :as protected]
            [graph.lain-editor.runtime.session :as editor]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.runner :as runner]))

(def mode :observe)

(def pi-config
  "Validation-only Pi pin. harness-session/session-extension -> pi-extension ->
  pi-transport/validate-config demands a pinned plugin and explicit
  provider/model/absolute cwd/tools even in :observe mode; nothing is contacted."
  {:plugin :pi-subagents :version "0.81.0"
   :provider "observe-only" :model "observe-only"
   :cwd (.getCanonicalPath (io/file ".")) :tools []})

(defn- bundle [identity] (harness/session-extension identity pi-config))

(defn install!
  "Idempotent: install-session-extension no-ops on an identical declaration, and
  extend!/restore! reuse the installed :session/extensions receipt."
  [session]
  (when-not (= :observe mode)
    (throw (ex-info "Glue supports observation only" {:mode mode})))
  (let [identity (or (::session-id @session) (str (random-uuid)))]
    (when-not (contains? (:session/extensions @session)
                         (extension/extension-id (bundle identity)))
      (swap! session assoc ::session-id identity)
      (runtime/install-session-extension! session (bundle identity)
                                          {:outbox-id (runtime-ids/boundary-outbox-id)})))
  @(::session-id @session))

(defn- wrap [original]
  (fn [session source]
    (let [identity (install! session)]
      ;; The bundle's fact leaves need the owning session's runner composition
      ;; (examples.lain.subagents.primitives/runner-transform), exactly as
      ;; harness-session/open! binds it around its own load.
      (binding [runner/*advance-transform*
                (runner/compose-advance-transforms runner/*advance-transform*
                                                   (primitives/runner-transform identity))]
        (original session source)))))

(defn activate! []
  (when (get (meta #'editor/load-checked!) ::glued)
    (throw (ex-info "Harness glue is already active" {})))
  (alter-var-root #'editor/load-checked! wrap)
  (alter-meta! #'editor/load-checked! assoc ::glued true)
  {:glued 'graph.lain-editor.runtime.session/load-checked! :mode mode})

(defn -main [& _]
  (let [_ (activate!)
        runtime (protected/start! (protected/configuration (System/getenv)))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (:close runtime)))
    (println (str "Harness-glued protected runtime: http://127.0.0.1:" (:port runtime)))
    @(promise)))

;;; ---------------------------------------------------------------------------
;;; MAIN-session observation: attach / detach without ownership.
;;;
;;; The MAIN session is created, clocked and disposed by its own owner (the harness
;;; session, examples.lain.subagents.harness-session). The editor is a subscriber:
;;; it registers an atom watch, projects immutable snapshots, and removes the watch.
;;; Every operation crosses one authorize gate, so an editor path cannot acquire
;;; lifetime (close/dispose/kill) or clock (schedule/stop/timer) authority even if the
;;; injected RPC/extension offers it.

(def observation-window
  "Projected snapshots retained per subscription. Bounded on purpose: an editor tab that
  stays open for days must not accumulate MAIN-session history." 80)

(def observation-ops
  "The complete editor-side vocabulary: subscribe, unsubscribe, read a snapshot."
  #{:subscribe :unsubscribe :snapshot})

(def lifetime-ops
  "Ownership operations. Rejected before dispatch: they belong to the session owner."
  #{:open :close :close-session :dispose :kill :stop :restart :reset :pump
    :start-clocks :stop-clocks :schedule-clocks :start-timer :stop-timer :timer
    :install-extension :mutate :reset-state})

(def ^:dynamic *rpc*
  "Session RPC/extension boundary used for observation. nil selects the watch-based
  adapter below; tests bind a fake that records each [op target] it is asked to perform."
  nil)

(defn- snapshot-of [state]
  "Project a session state to plain, comparable, allocation-cheap observation data.
  Networks, FactLogs and handler registries never cross the observer boundary."
  (if (map? state)
    {:next-order (get state :next-order)
     :tick (get state :next-order)
     :commit-tick (get state :runtime/commit-tick)
     :epoch (get state :program/epoch)
     :extensions (vec (sort-by pr-str (keys (:session/extensions state))))
     :clocks (vec (sort-by pr-str (keys (:clock/subscriptions state))))
     :traces (vec (sort-by pr-str (keys (:traces state))))
     :errors (count (:runtime/errors state))}
    {:opaque (str (type state))}))

(defn- watch-key [identity] (keyword "lain-editor-observer" identity))

(defn- default-rpc []
  {:subscribe (fn [{:keys [session] :as target} sink]
                (let [key (watch-key (:identity target))]
                  (add-watch session key (fn [_ _ _ state] (sink (snapshot-of state))))
                  (sink (snapshot-of @session))
                  {:subscription key :transport :session-watch :target target}))
   :unsubscribe (fn [{:keys [session]} subscription]
                  (remove-watch session subscription)
                  {:status :unsubscribed})
   :snapshot (fn [{:keys [session]}] (snapshot-of @session))})

(defn- rpc [op target & args]
  (when (contains? lifetime-ops op)
    (throw (ex-info "Editor paths may not own the MAIN session: lifetime/clock operation refused"
                    {:op op :posture mode})))
  (when-not (contains? observation-ops op)
    (throw (ex-info "Unknown session observation operation" {:op op})))
  (let [endpoint (get (or *rpc* (default-rpc)) op)]
    (when-not (ifn? endpoint)
      (throw (ex-info "Session boundary exposes no observation operation" {:op op})))
    (apply endpoint target args)))

(defn- observable-session [main]
  (let [session (if (map? main) (get main :session) main)]
    (when-not (and (instance? clojure.lang.IDeref session)
                   (instance? clojure.lang.IReference session))
      (throw (ex-info "The MAIN session is not observable: expected the session atom, or a
handle exposing :session"
                      {:main (str (type main))})))
    session))

(defn- target [main]
  (let [session (observable-session main)
        state (deref session)
        identity (or (when (map? main) (let [i (:identity main)] (when (string? i) i)))
                     (when (map? state) (::session-id state))
                     (str "main-" (System/identityHashCode session)))]
    {:identity identity :session session :handle (when (map? main) main)}))

(defn- alive? [{:keys [session handle]}]
  (let [closed (when (map? handle) (get handle :closed))]
    (boolean (and (some? (deref session))
                  (if (instance? clojure.lang.IDeref closed)
                    (not (seq @closed))
                    true)))))

(def ^:private subscriptions (atom {}))
(def ^:private ^Object subscriptions-lock (Object.))

(defn- key-of [{:keys [identity session]}]
  ;; Identity strings are the owner's, so two distinct MAIN sessions can legitimately share
  ;; one. The atom's identity hash keeps the observer keyed on the session itself.
  [identity (System/identityHashCode session)])

(defn- lookup
  "Resolve a reported sessionId to [key entry]. Ambiguous ids require the attachment map."
  [identity]
  (let [matches (filterv (fn [[k _]] (= (first k) identity)) @subscriptions)]
    (case (count matches)
      0 nil
      1 (first matches)
      (throw (ex-info "Ambiguous MAIN session id: pass the attachment returned by attach!"
                      {:sessionId identity :subscribed-sessions (count matches)})))))

(defn- resolve
  "Name/address any main-session reference (atom, owning handle, attachment map or sessionId)
  into {:identity .. :key .. :entry ..}. entry is nil when the editor holds no subscription."
  [reference]
  (if (and (not (string? reference)) (map? reference) (not (contains? reference :session))
           (contains? reference :sessionId))
    (let [identity (:sessionId reference)
          found (lookup identity)]
      {:identity identity :key (first found) :entry (second found)})
    (if (string? reference)
      (let [found (lookup reference)]
        {:identity reference :key (first found) :entry (second found)})
      (let [tgt (target reference)
            k (key-of tgt)]
        {:identity (:identity tgt) :key k :entry (get @subscriptions k) :target tgt}))))

(defn- sink-for [buffer]
  (fn [snapshot]
    ;; Runs on the MAIN session's own thread: never throw, never block, never retain the
    ;; session state itself (only the projection crosses here).
    (try
      (when-not (= snapshot (:latest @buffer))
        (swap! buffer (fn [{:keys [latest count tail]}]
                        {:latest snapshot :count (inc count)
                         :tail (take observation-window (cons snapshot tail))})))
      (catch Throwable _ nil))))

(defn- view [identity entry subscribed]
  (let [buffer (when-let [b (:buffer entry)] (deref b))
        tail (:tail buffer)]
    (cond-> {:sessionId identity
             :subscription (:subscription entry)
             :subscribers (get entry :subscribers 0)
             :subscribed (boolean subscribed)
             :observation (:latest buffer)
             :observations (count tail)
             :transport (:transport entry)}
      (zero? (get entry :subscribers 0)) (assoc :released true))))

(defn attached?
  "True while the editor holds a live subscription to this MAIN session."
  [reference]
  (boolean (:entry (resolve reference))))

(defn attach!
  "Subscribe to an independently owned MAIN session: the session atom itself, or a handle that
  exposes it (:session, optionally :identity/:closed, e.g. a Harness record).
  Observation only: the session is not created, extended, clocked or disposed here, and its own
  owner keeps full lifetime authority. Idempotent: re-attaching an already observed session
  reuses the single live subscription and issues no second subscribe call."
  [main]
  (let [{:keys [identity] :as tgt} (target main)
        k (key-of tgt)]
    (locking subscriptions-lock
      (if-let [entry (get @subscriptions k)]
        (do (swap! subscriptions update-in [k :subscribers] inc)
            (assoc (view identity (get @subscriptions k) true) :already-attached true))
        (let [buffer (atom {:latest nil :count 0 :tail ()})
              result (try (rpc :subscribe tgt (sink-for buffer))
                          (catch Throwable error
                            (throw (ex-info "MAIN session subscription failed"
                                            {:sessionId identity} error))))]
          (when-not (:subscription result)
            (throw (ex-info "MAIN session subscription returned no subscription"
                            {:sessionId identity :result result})))
          (let [entry {:subscription (:subscription result)
                       :buffer buffer :target tgt :subscribers 1
                       :transport (:transport result)}
                _ (swap! subscriptions assoc k entry)
                observed (try (rpc :snapshot tgt)
                              (catch Throwable error
                                (swap! subscriptions dissoc k)
                                (try (rpc :unsubscribe tgt (:subscription result))
                                     (catch Throwable _ nil))
                                (throw (ex-info "MAIN session observation failed"
                                                {:sessionId identity} error))))]
            (-> (view identity entry true)
                (assoc :observation observed
                       :already-attached false
                       :session-lived (alive? tgt)
                       :clocks-owned-by-editor false))))))))

(defn detach!
  "Release an editor subscription: unsubscribe only. The MAIN session is NOT closed, disposed,
  killed or de-clocked here; its owner keeps running it. Idempotent: only the last subscriber
  unsubscribes, earlier detach calls just drop one reference, and detaching a session the editor
  never subscribed to is a no-op that still reports whether the session survived."
  [attachment-or-main]
  (let [{:keys [identity key entry] :as ref} (resolve attachment-or-main)]
    (locking subscriptions-lock
      (if-not entry
        {:sessionId identity :subscribed false :subscription nil :subscribers 0
         :observations 0 :observation nil :released true :already-detached true
         :session-lived (try (alive? (:target ref (target attachment-or-main)))
                             (catch Throwable _ false))}
        (let [tgt (:target entry)
              remaining (dec (get entry :subscribers 1))]
          (if (pos? remaining)
            (do (swap! subscriptions assoc-in [key :subscribers] remaining)
                (-> (view identity (get @subscriptions key) true)
                    (assoc :already-detached true :released false :session-lived (alive? tgt))))
            (do (rpc :unsubscribe tgt (:subscription entry))
                (swap! subscriptions dissoc key)
                {:sessionId identity :subscribed false :subscription nil
                 :subscribers 0 :observations 0 :observation nil
                 :released true :already-detached false
                 :session-lived (alive? tgt)})))))))

(defn observation
  "Current projected snapshot of an attached MAIN session plus the bounded recent tail.
  Reads the editor-side buffer only: no pump, no poll, no clock pulse, no session mutation."
  [attachment-or-main]
  (let [{:keys [identity entry]} (resolve attachment-or-main)]
    (if-let [buffer (when entry @(:buffer entry))]
      {:sessionId identity :live true
       :observation (:latest buffer)
       :observations (into [] (reverse (:tail buffer)))
       :seen (:count buffer)}
      {:sessionId identity :live false :observation nil :observations [] :seen 0})))

(defn with-observation
  "Run f with the MAIN session subscribed, then release the subscription. The finally clause
  detaches the observer only; it can neither close nor clock the session."
  [main f]
  (let [attachment (attach! main)]
    (try (f)
         (finally (detach! attachment)))))

(defn subscriptions-snapshot
  "Editor-side observer registry: sessionId -> subscription diagnostics. Read-only."
  []
  (sort-by first
           (keep (fn [[k v]]
                   (when (map? v)
                     [k (select-keys v [:subscribers :subscription :transport])]))
                 @subscriptions)))
