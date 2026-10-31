;; W19 driver regressions — PURE fakes only.
;; NO live Docker / container / model call / bridge process / harness session is started here: the
;; bridge is exercised through an injectable :spawn returning a reified fake Process over in-memory
;; streams, the classpath probe through an injectable resource-urls var, the Harbor callbacks
;; through an injectable read-fn. Covers SUPERVISOR-REPAIR blocker 2 (launch must resolve
;; harness_session / harness_glue from the CANDIDATE, asserted through resource URLs) and the two
;; report.json launch-readiness gaps (driver-owned `pi_bridge.mjs --serve` process, :live
;; harbor-callbacks plumbing).
(ns lain.w19-classpath-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util.concurrent LinkedBlockingQueue]))

(load-file "/home/lain/experiments/lain-harness-flash-once-20261027/drivers/flash_once.clj")

(def root "/home/lain/experiments/lain-harness-flash-once-20261027")
(def candidate (str root "/candidate"))
(def deps-path (str root "/drivers/deps.edn"))
(def hs-file "examples/lain/subagents/harness_session.clj")
(def glue-file "graph/lain_editor/harness_glue.clj")
(def deploy "file:/home/lain/.local/share/lain-editor-deploy/current/backend/")
(def log-sink (io/file (str root "/observations/silent-resume-20261029/logs-W19/w19-tests.jsonl")))

(def resource-urls #'lain.flash-once/resource-urls)
(def classpath-verdict #'lain.flash-once/classpath-verdict)
(def startup-classpath-guard #'lain.flash-once/startup-classpath-guard)
(def require-candidate-classpath! #'lain.flash-once/require-candidate-classpath!)
(def bridge-spawn-config #'lain.flash-once/bridge-spawn-config)
(def start-bridge #'lain.flash-once/start-bridge)
(def stop-bridge #'lain.flash-once/stop-bridge)
(def harbor-callbacks #'lain.flash-once/harbor-callbacks)
(def live-session-config #'lain.flash-once/live-session-config)

;; log-path is a plain def in the driver: swap in the logs-W19 sink for each test and restore it, so
;; a test can never write the live root/logs/flash-once.jsonl.
(defn- isolate-log [f]
  (when (.exists log-sink) (.delete log-sink))
  (let [orig (var-get #'lain.flash-once/log-path)]
    (alter-var-root #'lain.flash-once/log-path (constantly log-sink))
    (try
      (f)
      (finally
        (alter-var-root #'lain.flash-once/log-path (constantly orig))))))

(use-fixtures :each isolate-log)

(defn- logged?
  ;; the driver log is concatenated JSON objects with no separator; a substring probe on the
  ;; "event":"<name>" field is enough and needs no brace-aware parse
  [event]
  (when (.exists log-sink)
    (str/includes? (slurp log-sink) (str "\"event\":\"" event "\""))))

;; ------------------------------------------------ 1. drivers/deps.edn is valid, candidate-first tools.deps data
(deftest deps-edn-is-valid-and-candidate-first
  (let [d (read-string (slurp deps-path))
        ps (:paths d)]
    (testing "top-level keys are all REAL tools.deps keys - no :deps:deps typo"
      (is (map? d))
      (is (not (contains? d :deps:deps))
          "the malformed :deps:deps key made tools.deps drop every dependency")
      (is (every? #{:paths :deps :aliases :mvn :roots :deps/manifest} (keys d))
          (str "keys=" (pr-str (keys d)))))
    (testing ":deps is a real map carrying the offline maven set W17 proved resolvable"
      (is (map? (:deps d)) "was silently absent before W19")
      (doseq [[sym ver] [['cheshire/cheshire "5.13.0"]
                         ['danlentz/clj-uuid "0.2.5"]
                         ['meander/epsilon "0.0.650"]
                         ['org.clojure/core.match "1.1.0"]
                         ['org.clojure/core.async "1.8.741"]
                         ['org.clojure/clojure "1.12.4"]]]
        (is (= ver (get-in d [:deps sym :mvn/version])) (str sym " pinned"))))
    (testing ":paths are absolute, candidate-first, and the deployed backend is GONE"
      (is (seq ps))
      (is (every? string? ps))
      (is (every? #(.isAbsolute (io/file %)) ps) "absolute: cwd=drivers must not matter")
      (is (every? #(.isDirectory (io/file %)) ps) (str "all exist: " (pr-str ps)))
      (is (= candidate (first ps)) "the candidate tree is classpath entry 0")
      (is (str/starts-with? (second ps) (str candidate "/")) "candidate sub-tree entry")
      (is (empty? (filter #(str/includes? % "lain-editor-deploy") ps))
          "no deployed-backend entry anywhere: a launch cannot resolve harness code by accident")
      (let [ci (.indexOf ps candidate)
            di (.indexOf ps (str root "/drivers"))]
        (is (zero? ci) "candidate is entry 0")
        (is (and (pos? di) (< ci di)) "candidate precedes the drivers entries")))))

;; ------------------------------------------------ 2. the RESOLVED classpath is candidate-only for harness code
(deftest resolved-classpath-is-candidate-first
  (testing "harness_session and harness_glue resolve from <root>/candidate/"
    (let [urls (resource-urls)]
      (is (= [hs-file glue-file] (sort (keys urls))) "both admissible resources are probed")
      (doseq [f [hs-file glue-file]]
        (let [u (get urls f)]
          (is (string? u) (str f " resolves at all"))
          (is (str/starts-with? (str u) (str "file:" candidate "/"))
              (str f " resolves from the candidate: " u))
          (is (not (str/includes? (str u) "lain-editor-deploy"))
              (str f " is not the deployed backend copy"))))))
  (testing "java.class.path itself carries no deployed-backend entry"
    (let [paths (str/split (System/getProperty "java.class.path") #":")]
      (is (empty? (filter #(str/includes? % "lain-editor-deploy") paths))
          "the deployed backend is not on the classpath at all")
      (is (some #(= % candidate) paths) "the candidate dir is on the classpath"))))

;; ------------------------------------------------ 3. the verdict function is pure and fails closed
(deftest classpath-verdict-logic
  (let [cand {hs-file (str "file:" candidate "/examples/lain/subagents/harness_session.clj")
              glue-file (str "file:" candidate "/editor/src/graph/lain_editor/harness_glue.clj")}]
    (testing "candidate URLs + repaired marker => ok"
      (is (true? (:ok (classpath-verdict cand true)))))
    (testing "candidate URLs but no candidate-only marker in the loaded code => not ok"
      (let [v (classpath-verdict cand false)]
        (is (false? (:ok v)))
        (is (false? (:candidate-code-loaded v)))))
    (testing "a deployed-backend URL => not ok, named under :deployed"
      (let [v (classpath-verdict (assoc cand hs-file (str deploy hs-file)) true)]
        (is (false? (:ok v)))
        (is (= [hs-file] (:deployed v)))
        (is (= [hs-file] (:outside-candidate v)))))
    (testing "an unresolvable resource => not ok, named under :missing"
      (let [v (classpath-verdict (assoc cand glue-file nil) true)]
        (is (false? (:ok v)))
        (is (= [glue-file] (:missing v)))))
    (testing "code from ANY other tree is as fatal as the deployed copy"
      (is (false? (:ok (classpath-verdict (assoc cand hs-file "file:/tmp/elsewhere/harness_session.clj") true)))))))

;; ------------------------------------------------ 4. the startup guard ABORTS a deployed shadow
(deftest startup-guard-aborts-on-deployed-shadow
  (testing "the real classpath passes the guard and logs classpath.ok"
    (let [g (startup-classpath-guard)]
      (is (true? (:ok g)) (str "live guard=" (pr-str (dissoc g :urls))))
      (is (require-candidate-classpath!) "the entry-point guard returns the verdict")
      (is (logged? "classpath.ok") "the probe is logged with its resolved URLs")))
  (testing "a DEPLOYED shadow makes the guard abort with classpath.mismatch"
    (let [orig (var-get resource-urls)
          shadow (fn [] {hs-file (str deploy hs-file)
                         glue-file (str "file:" candidate "/editor/src/" glue-file)})]
      (alter-var-root resource-urls (constantly shadow))
      (try
        (let [g (startup-classpath-guard)]
          (is (false? (:ok g)))
          (is (= [hs-file] (:deployed g)))
          (is (logged? "classpath.mismatch") "the mismatch is logged as classpath.mismatch")
          (is (thrown? Exception (require-candidate-classpath!))
              "start! refuses to dispatch a trial on a mismatched classpath"))
        (finally
          (alter-var-root resource-urls (constantly orig))))
      (is (true? (:ok (startup-classpath-guard))) "the guard passes again once the shadow is gone")))
  (testing "-main aborts NONZERO (exit 4) before starting anything when the guard says not-ok"
    (let [src (slurp (str root "/drivers/flash_once.clj"))]
      (is (re-find #"\(if-not \(:ok guard\)[\s\S]{0,200}\(System/exit 4\)" src)
          "-main exits 4 on classpath.mismatch")
      (is (re-find #"\(let \[guard \(startup-classpath-guard\)\][\s\S]{0,400}\(let \[st \(start-bridge\)" src)
          "the guard runs BEFORE the bridge process is spawned"))))

;; ------------------------------------------------ 5. report.json gap 1: the driver owns the bridge PROCESS
(deftest bridge-spawn-config-describes-the-serve-process
  (testing "one process: node pi_bridge.mjs --serve <private-config.json> inside the trial container"
    (let [cfg (bridge-spawn-config)]
      (is (every? string? (:argv cfg)))
      (is (= ["node" "/app/pi_bridge.mjs" "--serve" "/logs/agent/trial-config.json"]
             (:argv cfg)) "pi_bridge.mjs --serve <config> (pi_bridge.mjs:354-357)")
      (is (= "lain-trial-1" (:container cfg)))
      (is (= ["docker" "exec" "-i" "lain-trial-1" "node" "/app/pi_bridge.mjs" "--serve"
              "/logs/agent/trial-config.json"]
             (:container-argv cfg)))
      (is (= ["sh" "-c"] (take 2 (:shell cfg))) "the admitted sg-docker route")
      (doseq [frag ["sg docker -c '" "docker exec -i lain-trial-1"
                    "node /app/pi_bridge.mjs --serve" "2>> /home/lain/experiments/lain-harness-flash-once-20261027/logs/bridge-stderr.log"]]
        (is (.contains (last (:shell cfg)) frag) (str "shell cmd carries " frag))))
    (testing "overrides stay pure DATA - building a config spawns nothing"
      (let [cfg (bridge-spawn-config {:container "other" :config-path "/logs/agent/x.json"})]
        (is (= "other" (:container cfg)))
        (is (= ["node" "/app/pi_bridge.mjs" "--serve" "/logs/agent/x.json"] (:argv cfg)))
        (is (.contains (last (:shell cfg)) "docker exec -i other"))
        (is (every? #(or (string? %) (vector? %)) (vals cfg))
            "plain data only - no process handle in the config")))))

;; ------------------------------------------------ 6. start-bridge OWNS the process it starts (fake process)
(deftest start-bridge-owns-and-pumps-injected-process
  (let [destroyed (atom false)
        alive (atom true)
        frames "{\"id\":1,\"result\":{\"status\":\"running\"}}\n{\"id\":2,\"result\":{\"status\":\"completed\"}}\n"
        fake (proxy [Process] []
               (getOutputStream [] (ByteArrayOutputStream.))
               (getErrorStream [] (ByteArrayInputStream. (byte-array 0)))
               (getInputStream [] (ByteArrayInputStream. (.getBytes frames "utf-8")))
               (waitFor [] 0)
               (exitValue [] 0)
               (destroy [] (reset! destroyed true) (reset! alive false) nil)
               (destroyForcibly [] (reset! destroyed true) (reset! alive false) nil)
               (isAlive [] @alive))
        spawned (atom nil)
        st (start-bridge {:spawn (fn [cfg] (reset! spawned cfg) fake)})]
    (testing "the driver holds the process it started and handed it the spawn config"
      (is (= fake (:proc st)) "the Process is part of the driver-owned state")
      (is (= (bridge-spawn-config) @spawned) "spawn got the spawn config (nothing real was spawned)")
      (is (= (:argv (bridge-spawn-config)) (:argv (:config st)))))
    (testing "the daemon reader thread forwards EVERY frame until EOF (the W15 hang bug)"
      (loop [i 0]
        (when (and (< i 150) (< (.size ^LinkedBlockingQueue (:q st)) 2))
          (Thread/sleep 20)
          (recur (inc i))))
      (is (= 2 (count (vec (.toArray ^LinkedBlockingQueue (:q st)))))
          "both frames were pumped before EOF, the loop did not stop after the first")
      (is (instance? clojure.lang.IDeref (:seq st)) "the RPC id sequence is per-process state"))
    (testing "stop-bridge destroys the process the driver started - never an orphaned child"
      (stop-bridge st)
      (is @destroyed "stop-bridge destroyed the owned process")
      (is (false? (.isAlive ^Process (:proc st))) "and it is really gone"))))

(deftest stop-bridge-tolerates-missing-and-dead-processes
  (testing "no process at all (constructor-only state) is not an error"
    (is (map? (stop-bridge {:proc nil :w nil}))))
  (testing "an already-dead process is not force-destroyed twice"
    (let [p (proxy [Process] [] (isAlive [] false)
                     (destroyForcibly [] (throw (IllegalStateException. "twice"))))]
      (is (map? (stop-bridge {:proc p :w nil}))))))

;; ------------------------------------------------ 7. report.json gap 2: :live harbor-callbacks plumbing
(deftest harbor-callbacks-satisfy-the-live-contract
  (testing "open! :live requires exactly :prepare / :grade / :dispose! (harness_session.clj:109-111)"
    (let [cbs (harbor-callbacks)]
      (is (= #{:prepare :grade :dispose!} (set (keys cbs))))
      (is (every? ifn? (vals cbs)) "all three callable, or open! throws before the trial starts"))))

(deftest prepare-derives-expectations-from-the-fixture
  (testing "prepare returns a context map whose :expected is DERIVED from the fixture"
    (let [cbs (harbor-callbacks {:read-fn (fn [_] "3 4\n")})
          ctx ((:prepare cbs) {:session "lain-trial/a1"
                               :assignment-id "a1"
                               :task "add the two numbers in task.txt"})]
      (is (map? ctx) "a context map, per harbor_extension.clj:122-132")
      (is (= 7 (:expected ctx)) "3 + 4, not a driver-side constant")
      (is (= "a1" (:assignment-id ctx)) "harbor passes :assignment-id (not :assignment)")
      (is (string? (:instructions ctx)))
      (is (logged? "harbor.prepare"))))
  (testing "a malformed fixture fails closed instead of grading against a guess"
    (is (thrown? Exception
                 ((:prepare (harbor-callbacks {:read-fn (fn [_] "3")}))
                  {:session "s" :assignment-id "a" :task "t"})))))

(deftest grade-collects-evidence-and-fails-lies
  (testing "the three report.json checks: answer file, arithmetic, child provenance"
    (let [cbs (harbor-callbacks {:read-fn (fn [f] (if (str/ends-with? (str f) "answer.txt") "7\n" "3 4"))})
          a ((:grade cbs) {:session "s"
                           :assignment-id "a1"
                           :task "t"
                           :context {:expected 7}
                           :result {:child "c1"}})]
      (is (map? a))
      (is (true? (:passed a)) (str a))
      (is (= 3 (:assertions a)))
      (is (every? :passed (:checks a)) (str (:checks a)))))
  (testing "a lying answer file fails the arithmetic check"
    (let [a ((:grade (harbor-callbacks {:read-fn (fn [_] "6")}))
             {:context {:expected 7} :result {:child "c1"}})]
      (is (false? (:passed a)) "the aggregate verdict fails the lie")
      (is (true? (:passed (first (filter #(= :answer-file-present (:check %)) (:checks a)))))
          "the file IS present - presence is not what fails")
      (is (false? (:passed (first (filter #(= :arithmetic-correct (:check %)) (:checks a)))))
          "6 != 3+4 is what fails")
      (is (true? (:passed (first (filter #(= :child-provenance-present (:check %)) (:checks a))))))))
  (testing "a reported answer without child provenance fails"
    (is (false? (:passed ((:grade (harbor-callbacks {:read-fn (fn [_] "7")}))
                          {:context {:expected 7} :result nil}))))))

(deftest dispose-takes-a-bare-session-and-reports-a-receipt
  (let [r ((:dispose! (harbor-callbacks)) "lain-trial/a1")]
    (is (map? r) "a receipt map, per harbor_extension.clj:141-144")
    (is (true? (:cleanup-verified r)))
    (is (string? (:note r)) "the receipt says what it does and does not own")
    (is (logged? "harbor.dispose"))))

(deftest live-session-config-wires-both-launch-gaps
  (testing "the config passed to hs/open! carries pi-hooks AND the harbor callbacks, opening nothing"
    (let [st {:proc nil :w (ByteArrayOutputStream.) :q (LinkedBlockingQueue.) :seq (atom 0)}
          cfg (live-session-config st (harbor-callbacks))]
      (is (= :live (:mode cfg)) "LIVE mode - the W17 fake-substituted path is not the product")
      (is (= #{:plugin :version :provider :model :cwd :tools} (set (keys (:pi-config cfg)))))
      (is (= "0.19.1" (get-in cfg [:pi-config :version])) "pinned plugin version (pi_transport.clj:38)")
      (is (= "/app" (get-in cfg [:pi-config :cwd])) "bridge-mandated absolute cwd")
      (is (every? #(fn? (get-in cfg [:pi-hooks %])) [:call! :reconcile! :dispose!])
          "pi_transport:184-186 requires real fns")
      (is (every? ifn? (map (:harbor-callbacks cfg) [:prepare :grade :dispose!]))
          "hs/open! :live throws without these")
      (is (nil? (:proc st)) "no process, session or container was touched by building the config"))))
