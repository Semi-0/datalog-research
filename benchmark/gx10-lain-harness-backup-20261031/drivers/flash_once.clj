(ns lain.flash-once
  "Plumbing ONLY - no second orchestrator, no host-side grading.

  1. keep exactly one admitted trial container and one bridge process alive,
  2. implement the Pi RPC hooks (call!, reconcile!, dispose!) as newline-delimited JSON
     RPC to the production pi_bridge.mjs (statuses per pi_transport.clj:19-24),
  3. provide the Harbor callbacks the framework REQUIRES (prepare, grade, dispose!),
  4. perform the host's documented timing duty: pump!/poll! (HARNESS.md:130-131).
     Eligibility and every transition rule stay inside Lain; the assessment stays
     framework-owned (HARNESS.md:33). A wall-clock guard only stops, never decides.

  start! is the entry point an editor-owned backend JVM calls; -main is for headless
  bring-up and validation of the same code path."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.harness-session :as hs])
  (:import [java.io BufferedReader InputStreamReader OutputStream]
           [java.lang ProcessBuilder]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]
           [java.nio.file Files]))

(def root "/home/lain/experiments/lain-harness-flash-once-20261027")
(def container "lain-trial-1")
(def sid "lain-trial/a1")
(def assignment-id "a1")
(def started-at (System/currentTimeMillis))
(def log-path (io/file root "logs" "flash-once.jsonl"))
(def guard-ms 1200000)                                    ; safety stop only, never a phase decision
(def statuses #{:launched :reconciled :completed :stopped :uncertain :failed :running})

(defn- read-raw [p] (String. (Files/readAllBytes (.toPath (io/file p))) "utf8"))
(defn- log [event data]
  (io/make-parents log-path)
  (spit log-path (json/generate-string
                  (merge {:t (- (System/currentTimeMillis) started-at) :event event} data))
           :append true))

(defn mem-available-gib []
  (when-let [l (first (filter #(str/starts-with? % "MemAvailable:") (str/split-lines (read-raw "/proc/meminfo"))))]
    (/ (Double/parseDouble (second (re-find #"(\d+)" l))) 1048576.0)))
(defn memory-ok? [] (let [total (/ (Double/parseDouble (second (re-find #"(\d+)" (first (filter #(str/starts-with? % "MemTotal:") (str/split-lines (read-raw "/proc/meminfo"))))))) 1048576.0)]
                      (>= (mem-available-gib) (* 0.2 total))))

;; ---------------------------------------------------------------- classpath provenance (B2)
(def candidate-prefix (str root "/candidate/"))
(def deployed-marker "lain-editor-deploy")
(def classpath-resources
  "Resources whose CODE must come from the candidate tree for any launch to be admissible:
   the harness session extension (examples.lain.subagents.harness-session) and the editor-side
   glue (graph.lain-editor.harness-glue). SUPERVISOR-REPAIR blocker 2 forbids resolving either of
   them from the deployed backend. Undashed ns names map to these underscored file names."
  {"examples/lain/subagents/harness_session.clj" 'examples.lain.subagents.harness-session
   "graph/lain_editor/harness_glue.clj"          'graph.lain-editor.harness-glue})

(def candidate-only-markers
  "Vars present ONLY in the repaired candidate harness session. The deployed backend still carries
   the old host-side single pending-action chooser, so these spot-check that the LOADED code is the
   repaired candidate build, not merely some file under candidate/. ANY marker suffices: W18 owns
   this file and may rename one of them while the URL check stays authoritative."
  '[examples.lain.subagents.harness-session/current-pi-intents
    examples.lain.subagents.harness-session/eligible-actions
    examples.lain.subagents.harness-session/*pump-ownership*])

(defn resource-urls
  "Resolve the admissible resources on the LIVE classpath (resource lookup only, no loading, no
   process, no I/O beyond the classpath) to absolute URL strings; nil when unresolvable."
  []
  (into (sorted-map) (map (fn [r] [r (some-> (io/resource r) str)])) (keys classpath-resources)))

(defn classpath-verdict
  "PURE admissibility check of an already-resolved probe: every resource must resolve and every URL
   must live under <root>/candidate/. A deployed-backend URL, an out-of-tree URL or a missing
   resource is fatal; marker-present? comes from candidate-only-markers in the LOADED namespaces."
  [urls marker-present?]
  (let [missing  (filterv #(not (string? (urls %))) (keys classpath-resources))
        deployed (filterv #(when-let [u (urls %)] (str/includes? u deployed-marker))
                          (keys classpath-resources))
        outside  (filterv #(when-let [u (urls %)] (not (str/includes? u candidate-prefix)))
                          (keys classpath-resources))]
    {:ok                  (boolean (and (empty? missing) (empty? outside) (boolean marker-present?)))
     :missing             missing
     :deployed            deployed
     :outside-candidate   outside
     :candidate-code-loaded (boolean marker-present?)
     :urls                urls}))

(defn candidate-code-loaded? [] (boolean (some find-var candidate-only-markers)))

(defn startup-classpath-guard
  "Probe the live classpath BEFORE any dispatch and return the verdict; logs classpath.ok or
   classpath.mismatch. Never exits - the caller (-main / start!) decides."
  []
  (let [v (classpath-verdict (resource-urls) (candidate-code-loaded?))]
    (log (if (:ok v) "classpath.ok" "classpath.mismatch") v)
    v))

(defn require-candidate-classpath!
  "Entry-point guard used by start! (the editor-owned backend JVM path): abort loudly, in-process,"
  []
  (let [v (startup-classpath-guard)]
    (when-not (:ok v)
      (throw (ex-info "classpath.mismatch: harness code did not resolve from the candidate tree"
                      (dissoc v :urls))))
    v))

;; ---------------------------------------------------------------- bridge transport
(defn- pump-reader! [^BufferedReader r q]
  "Reader-loop body: forward EVERY line to the queue until EOF (nil), i.e. recur, not one shot."
  (loop [line (.readLine r)]
    (when line (.put q line) (recur (.readLine r)))))

(def bridge-script "/app/pi_bridge.mjs")
(def bridge-config-path "/logs/agent/trial-config.json")
(def bridge-stderr-log "/home/lain/experiments/lain-harness-flash-once-20261027/logs/bridge-stderr.log")

(defn bridge-spawn-config
  "Spawn spec for the ONE bridge process the driver owns (report.json gap 1: 'no production adapter
   spawns node pi_bridge.mjs --serve'). The bridge speaks newline-delimited JSON on stdin/stdout
   (pi_bridge.mjs:354-357) inside the admitted trial container, so the driver starts it through the
   admitted `sg docker` route, owns the Process, pumps it and destroys it. PURE DATA - building the
   config spawns nothing; tests assert this shape and never spawn. Omit opts for the production spec."
  [& [{:keys [node script config-path stderr-log serve-args] :or {node "node"} :as opts}]]
  (let [container   (or (:container opts) container)
        script      (or script bridge-script)
        config-path (or config-path bridge-config-path)
        stderr-log  (or stderr-log bridge-stderr-log)
        argv        (into [node script] (or serve-args ["--serve" config-path]))
        inner       (into ["docker" "exec" "-i" container] argv)]
    {:argv         argv                                    ; node pi_bridge.mjs --serve <config>
     :container    container
     :container-argv inner
     :stderr-log   stderr-log
     :shell        ["sh" "-c" (str "sg docker -c '" (str/join " " inner) " 2>> " stderr-log "'")]}))

(defn- spawn-shell-process
  "The REAL spawn used by -main/start-bridge: start (and thereby own) the bridge process."
  [cfg]
  (.start (ProcessBuilder. (into-array String (:shell cfg)))))

(defn start-bridge
  "Own the bridge process: spawn it (injectable :spawn for tests - a fake Process over in-memory
   streams; the default spawns the container-exec), then wire the newline-JSON transport around it
   with one daemon reader thread that pumps EVERY frame until EOF."
  [& [{:keys [spawn config]}]]
  (let [cfg   (merge (bridge-spawn-config) config)
        spawn (or spawn spawn-shell-process)
        proc  ^Process (spawn cfg)
        q     (LinkedBlockingQueue.)
        w     (.getOutputStream proc)
        r     (BufferedReader. (InputStreamReader. (.getInputStream proc) "utf8"))
        t     (Thread. ^Runnable #(try (pump-reader! r q)
                                       (catch Throwable e (.put q (json/generate-string {"error" (str e)})))))]
    (.setDaemon t true) (.start t)
    {:proc proc :reader t :w w :q q :seq (atom 0) :config cfg}))

(defn stop-bridge
  "Driver-side ownership ends here: the bridge process the driver started is destroyed, never
   orphaned. Safe to call twice / on a dead process."
  [st]
  (when-let [p ^Process (:proc st)]
    (when (.isAlive p) (.destroyForcibly p)))
  (when-let [w (:w st)] (try (.close w) (catch Exception _ nil)))
  st)

(defn- eid->wire [e] (when (sequential? e) (mapv #(if (keyword? %) (name %) (str %)) e)))
(defn- frame-from [ctx]
  "Wire envelope per bridge-contract/contract.md §1: session (string) and effect-id (array) are
   mandatory; tool xor operation; launch-effect-id only for reconcile; assignment-context only
   instructions/trial-id. Nothing else from the Clojure context crosses the wire."
  (cond-> {:session (:session ctx) :effect-id (eid->wire (:effect-id ctx))}
    (:tool ctx) (-> (assoc :tool (:tool ctx)) (assoc :arguments (:arguments ctx)))
    (:operation ctx) (assoc :operation (:operation ctx))
    (:launch-effect-id ctx) (assoc :launch-effect-id (eid->wire (:launch-effect-id ctx)))
    ;; select the PUBLIC keys FROM THE CONTEXT and assoc them onto the wire frame;
    ;; (update :assignment-context select-keys ...) would select-keys on the wire map's own
    ;; absent (nil) value and silently send {} whenever ctx carries an :assignment-context.
    (:assignment-context ctx) (assoc :assignment-context
                                     (select-keys (:assignment-context ctx)
                                                  #{:instructions :trial-id}))))

(defn- rpc [{:keys [w q seq]} request-frame timeout-ms]
  (let [id (swap! seq inc)
        _ (.write ^OutputStream w (.getBytes (str (json/generate-string (assoc request-frame :id id)) "\n") "utf8"))
        _ (.flush ^OutputStream w)]
    (loop [deadline (+ (System/currentTimeMillis) (or timeout-ms 120000)) acc []]
      (let [left (- deadline (System/currentTimeMillis))]
        (when (pos? left)
          (when-let [line (.poll q left TimeUnit/MILLISECONDS)]
            (let [frame (try (json/parse-string line true) (catch Exception _ {:raw line}))]
              (if (= id (:id frame))
                {:frame frame :saw (conj acc (:status frame))}
                (do (log "bridge.frame" {:unmatched-id (:id frame)
                                         :frame-frame (:status frame)})
                    (recur deadline (conj acc frame)))))))))))

;; ---------------------------------------------------------------- Pi RPC hooks
(defn- launch-attempted? [request]
  "True iff the REQUEST whose frame was already invoked on the bridge was a launch (spawn)
   or a reconcile - the two frames whose malformed reply means UNCERTAIN delivery (contract.md §4)."
  (and (map? request)
       (or (= "subagent_spawn_batch" (:tool request))
           (= "reconcile" (:operation request)))))

(defn- wire->status
  "Normalize a bridge reply (envelope {id,result}; status lives in result, closed 7-status
   vocabulary per contract.md §2) to pi_transport's bounded response map. The child identity
   comes ONLY from result.child (contract.md §3) - never a stringified payload. An unrecognized
   shape fails closed as :failed for collect/stop frames; but for a frame whose launch/reconcile
   was ALREADY INVOKED (post-invocation malformed or absent reply) the delivery is UNCERTAIN - a
   child may exist, to be reconciled by the durable launch-effect-id and NEVER relaunched
   (W7 reconcile-never-relaunch; contract.md §4). (wire->status frame) alone keeps the old
   meaning: unknown shape -> :failed."
  ([frame] (wire->status frame nil))
  ([frame request]
   (let [res (when (map? frame) (:result frame))
        wire (some-> (:status res) str/lower-case)
        child (when (string? (:child res)) (:child res))]
    (cond
      (= "completed" wire) (merge {:status :completed :result (:result res)} (when child {:child child}))
      (= "running" wire) (merge {:status :running} (when child {:child child}))
      (contains? #{"launched" "reconciled"} wire) {:status (keyword wire) :child child}
      (= "stopped" wire) (merge {:status :stopped :cleanup-verified (true? (:cleanup-verified res))}
                                (when child {:child child}))
      (contains? #{"failed" "uncertain"} wire)
      (merge {:status (keyword wire)
              :failure (or (:failure res) {:message (or (:error frame) (str "bridge reported " wire))})}
             (when child {:child child}))
      :else (let [msg (or (:error frame)
                          (str "unrecognized bridge response shape: "
                               (pr-str (when (map? res) (:status res)))))]
              (if (launch-attempted? request)
                (merge {:status :uncertain
                        :failure {:message (str msg " - launch/reconcile already invoked, delivery"
                                                " uncertain; reconcile via durable launch-effect-id,"
                                                " never relaunch")}}
                       (when child {:child child}))
                {:status :failed :failure {:message msg}}))))))

(defn pi-hooks [st]
  {:call! (fn [ctx]
            (log "pi.call" {:tool (:tool ctx) :effect-id (:effect-id ctx) :trial (:trial ctx)})
            (let [req (frame-from ctx)
                  {:keys [frame]} (rpc st req 180000)]
              (log "pi.call.reply" {:effect-id (:effect-id ctx) :status (get-in frame [:result :status])
                                    :error (:error frame)})
              (let [resp (wire->status frame req)]
                (if-not (statuses (:status resp))
                  {:status :failed :failure {:message "unmapped bridge response"}}
                  (select-keys resp #{:status :child :result :failure :cleanup-verified})))))
   :reconcile! (fn [ctx]
                 ;; reconcile from the bridge's durable launch receipt by stable effect id
                 ;; (contract.md §1/§2) - NEVER relaunch, never a listing-guess.
                 (log "pi.reconcile" {:launch-effect-id (:launch-effect-id ctx)})
                 (let [req (frame-from (assoc ctx :operation "reconcile"))]
                   (wire->status (:frame (rpc st req 60000)) req)))
   :dispose! (fn [ctx]
               (log "pi.dispose" {:session (:session ctx)})
               ;; dispose ack carries {cleanup-verified true} and nothing else (contract.md §2 row 9);
               ;; pi_transport close! refuses disposal that did not verify cleanup.
               (let [{:keys [frame]} (rpc st (frame-from (assoc ctx :operation "dispose"
                                                                 :effect-id ["dispose"])) 60000)]
                 {:cleanup-verified (true? (get-in frame [:result :cleanup-verified]))
                  :note (or (:error frame) "bridge dispose receipt")}))})

;; ---------------------------------------------------------------- Harbor callbacks
(def task-fixture (io/file root "stage" "app" "task.txt"))
(def answer-fixture (io/file root "stage" "app" "answer.txt"))

(defn- read-evidence [p]
  "Grade is evidence COLLECTION: an answer file the child never wrote is absence of
   evidence (a failing check), never an effect failure that would poison the pump."
  (try (read-raw p)
       (catch java.nio.file.NoSuchFileException _ nil)))

(defn harbor-callbacks
  "The Harbor callbacks the framework REQUIRES in :live mode (harness_session.clj open! throws
   without :prepare/:grade/:dispose! - report.json gap 2). They are evidence collection only; the
   AUTHORITATIVE assessment stays framework-owned (HARNESS.md:33). Every host read goes through the
   injectable read-fn, so the wiring is validated with pure fakes and no live trial is needed.
   Callback signatures per harbor_extension.clj:122-146: prepare/grade get a request map, dispose!
   gets a BARE session string."
  [& [{:keys [task-file answer-file read-fn] :or {read-fn read-evidence}}]]
  (let [task-file   (or task-file task-fixture)
        answer-file (or answer-file answer-fixture)]
    {:prepare (fn [{:keys [session assignment-id task] :as _request}]
                ;; the expected value is DERIVED from the task fixture data, not a driver constant
                (let [nums (some->> (read-fn task-file) (re-seq #"-?\d+") (mapv parse-long))
                      _ (when-not (= 2 (count nums))
                          (throw (ex-info "Task fixture must contain exactly two integers"
                                          {:task-file (str task-file) :nums nums})))
                      expected (apply + nums)]
                  (log "harbor.prepare" {:session session :assignment assignment-id
                                         :expected expected})
                  {:session session :assignment assignment-id :assignment-id assignment-id
                   :task task :expected expected
                   :instructions "read /app/task.txt, sum the two integers, write to /app/answer.txt"}))
     :grade (fn [{:keys [session assignment-id context result] :as _request}]
              ;; evidence collection only; the AUTHORITATIVE assessment is the framework's,
              ;; which enriches the shared assignment facts (HARNESS.md:33).
              (let [answer (some-> (read-fn answer-file) str/trim)
                    checks [{:check :answer-file-present :passed (boolean (seq answer))}
                            {:check :arithmetic-correct :passed (= answer (str (:expected context)))}
                            {:check :child-provenance-present :passed (boolean (:child result))}]]
                (log "harbor.grade" {:answer answer :expected (:expected context)
                                     :passed (every? :passed checks)})
                {:passed (boolean (every? :passed checks)) :checks checks
                 :assertions (count checks)
                 :session session :assignment-id assignment-id}))
     :dispose! (fn [session]
               ;; invoked with a bare session string (harbor_extension.clj:146); Harbor owns no
               ;; external child - the VERIFIED child cleanup is the Pi dispose receipt
               ;; (pi_transport.clj close! refuses an unverified one).
     (log "harbor.dispose" {:session session})
     {:cleanup-verified true
      :note (str "session-scoped harbor state only for " (pr-str session)
                 "; stage/app retained as trial evidence; Pi dispose verifies child cleanup")})}))

;; ---------------------------------------------------------------- session bring-up
(def pi-config-map
  "Pinned Pi config (pi_transport pins + report.json pi_config); :app cwd is bridge-mandated."
  {:plugin :pi-better-subagents :version "0.19.1"
   :provider "gx10-flash-next-autoround"
   :model "qwen3.8-flash-next-autoround"
   :cwd "/app" :tools ["read" "bash"]})

(defn live-session-config
  "The :live hs/open! config: explicit Pi RPC hooks (the driver-owned bridge) plus the REQUIRED
   Harbor callbacks. Pure data + closures - tests validate the wiring without a session."
  [st callbacks]
  {:mode :live
   :pi-config pi-config-map
   :pi-hooks (pi-hooks st)
   :harbor-callbacks callbacks})

(defn start!
  "Create/extend the MAIN harness session. Returns the harness owner.
   Aborts before dispatch if the harness code would come from the DEPLOYED backend (B2)."
  [st task]
  (require-candidate-classpath!)
  (hs/open! sid (live-session-config st (harbor-callbacks))))

(defn submit! [h task]
  (hs/observe! h :socket
               (facts/from-facts sid [{:session sid :id "cmd-1" :order 0
                                       :kind :socket/command
                                       :command-kind "assignment/submitted"
                                       :command {:assignment-id assignment-id :task task}}]))
  (log "socket.submitted" {:assignment-id assignment-id}) h)

(defn assessed? [h]
  (some #(when (= :harbor/assessed (:kind %)) %)
        (get-in (hs/artifacts h) [:facts :harbor-in])))

(defn- probe-summary
  "Diagnostic-only snapshot through PUBLIC APIs (coordinator request after live attempt 1 showed
   zero emissions): port fact counts, receipt effect-ids, next-order. Adds no policy or timing."
  [h]
  (let [a (hs/artifacts h)]
    {:next-order (:next-order a)
     :facts (update-vals (:facts a) (fn [v] (try (count v) (catch Exception e -1))))
     :harbor-receipts (mapv (comp vec :effect-id) (:harbor-receipts a))
     :socket-receipts (mapv (comp vec :effect-id) (:socket-receipts a))}))

(defn- tick-decision
  "Pure loop-control decision for -main (timing duty ONLY; Lain owns eligibility/transitions).
   Any pump/poll EXCEPTION stops the run NONZERO; the wall-clock guard only STOPS - and its stop
   is nonzero too, never a silent exit 0; only a framework assessment finishes with exit 0."
  [{:keys [pump-error poll-error assessed? elapsed-ms guard-ms]}]
  (cond
    (or pump-error poll-error) {:action :stop :exit 1 :reason :pump-or-poll-error}
    assessed? {:action :finish :exit 0 :reason :assessed}
    (> elapsed-ms guard-ms) {:action :stop :exit 3 :reason :guard-timeout}
    :else {:action :recur :exit nil :reason nil}))

(defn -main [& _]
  (let [task "Read /app/task.txt and answer it. The file contains two integers; write their sum to /app/answer.txt."]
    (if-not (memory-ok?)
      (do (log "abort.memory" {:mem-available-gib (mem-available-gib)}) (System/exit 2))
      (let [guard (startup-classpath-guard)]
        (if-not (:ok guard)
          ;; B2: harness_session / harness_glue would resolve OUTSIDE the candidate tree: never launch
          (do (log "abort.classpath" (dissoc guard :urls)) (System/exit 4))
          (let [st (start-bridge) owner (atom nil)]
            (log "bridge.started" {:pid (.pid ^Process (:proc st)) :argv (get-in st [:config :argv])
                                   :mem-available (mem-available-gib)})
            (try
              (let [h (do (reset! owner (start! st task)) (submit! @owner task) @owner)]
                (log "probe.initial" (probe-summary h))
                (loop [round 0]
                  ;; timing duty only; Lain decides eligibility and every transition
                  (let [pumped (try (hs/pump! h) (catch Exception e {:pump-error (ex-message e)}))
                        polled (try (hs/poll! h (str "poll-" round)) (catch Exception e {:poll-error (ex-message e)}))
                        assessed (boolean (assessed? h))
                        decision (tick-decision {:pump-error (:pump-error pumped)
                                                 :poll-error (:poll-error polled)
                                                 :assessed? assessed
                                                 :elapsed-ms (- (System/currentTimeMillis) started-at)
                                                 :guard-ms guard-ms})]
                    (log "tick" {:round round :pump-error (:pump-error pumped) :poll-error (:poll-error polled)
                                 :assessed assessed
                                 :action (:action decision) :reason (:reason decision)
                                 :mem-available (mem-available-gib)})
                    (when (zero? (mod round 20)) (log "probe.tick" (probe-summary h)))
                    (case (:action decision)
                      ;; assessed by the framework: persist artifacts, verified close, exit 0
                      :finish
                      (do (let [arts (hs/artifacts h) closed (hs/close! h)]
                            (spit (io/file root "artifacts.json") (json/generate-string arts {:pretty true}))
                            (log "done" {:closed closed :elapsed-ms (- (System/currentTimeMillis) started-at)})
                            (stop-bridge st))
                          (System/exit 0))
                      ;; pump/poll exception OR safety-timeout stop: ALWAYS nonzero exit
                      :stop
                      (do (log "stop.nonzero" {:reason (:reason decision) :round round :exit (:exit decision)
                                               :pump-error (:pump-error pumped) :poll-error (:poll-error polled)})
                          (try (hs/close! h) (catch Exception _ nil))
                          (stop-bridge st)
                          (System/exit (:exit decision)))
                      (do (Thread/sleep 4000) (recur (inc round)))))))
              (catch Exception e
                (log "fatal" {:error (ex-message e) :data (ex-data e)
                              :trace (take 8 (map str (.getStackTrace ^Throwable e)))})
                (when-let [h @owner] (try (hs/close! h) (catch Exception _ nil)))
                (stop-bridge st)
                (System/exit 1)))))))))
