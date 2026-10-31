(ns lain.w23-contract-test
  "W23 regression contract for the live-mode stall at assignments=0.

  The stall was NOT a mode/config asymmetry: `.lain` directives (load-lain,
  load-primitive-environment) resolve as FILES against the process working directory, so a driver
  launched from drivers/ installed inlet cells with no composition behind them - and harness open!
  could not notice, because file_loader/load-session-from-source discards the value that carries
  the load error (file_loader.clj:84-90) and nested failures only ever land in effect receipts.

  Every session here is opened with the driver's OWN live-session-config and the production
  declaration, with in-memory fakes only: no docker, no node bridge, no model server, no container,
  no host trial path."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.harness-session :as hs])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream BufferedReader InputStreamReader PipedInputStream PipedOutputStream]))

(load-file "/home/lain/experiments/lain-harness-flash-once-20261027/drivers/flash_once.clj")
(alias 'd (the-ns 'lain.flash-once))

(def root "/home/lain/experiments/lain-harness-flash-once-20261027")
(def logs (io/file root "observations/silent-resume-20261029/logs-W23"))
(def scratch-log (io/file logs "contract-scratch.jsonl"))
(def task-fixture (io/file logs "contract-task.txt"))
(def answer-fixture (io/file logs "contract-answer.txt"))
(def composition "examples/lain/subagents/composition.lain")
(def empty-bytes (byte-array 0))
(def max-rounds 8)

(use-fixtures :once (fn [f]
                      ;; scratch evidence fixtures, never the live /app or /tmp trial files
                      (spit task-fixture "Read /app/task.txt and answer it. Two integers: 3 and 4.\n")
                      (spit answer-fixture "7\n")
                      (try (f) (finally (doseq [f [scratch-log task-fixture answer-fixture]]
                                         (.delete ^java.io.File f))))))

(load-file "/home/lain/experiments/lain-harness-flash-once-20261027/drivers/test/w23_fake_bridge.clj")
(alias 'fb (the-ns 'lain.w23-fake-bridge))

(defn- open-live-session! []
  "hs/open! with the driver's production live-session-config: real Pi RPC hooks over a fake bridge
   process, real Harbor callbacks over an injected reader. Records the prepare requests."
  []
  (let [st (d/start-bridge {:spawn (fn [_] (fb/create))})
        prepared (atom [])
        base (d/harbor-callbacks :task-file task-fixture :answer-file answer-fixture
                                :read-fn (fn [p] (try (slurp p) (catch Exception _ nil))))
        callbacks (update base :prepare (fn [f] (fn [request]
                                                 (let [context (f request)]
                                                   (swap! prepared conj (merge request context))
                                                   context))))]
    {:h (hs/open! d/sid (d/live-session-config st callbacks)) :prepared prepared}))

(defn- kinds [art port] (mapv :kind (get-in art [:facts port])))

(defn- advance!
  "Bounded propagation: pump/poll until the stop artifact predicate holds or max-rounds is spent.
   Always reports the LAST artifacts (a stalled session must show what it reached, not nil)."
  [h stop-art]
  (loop [round 0 art (hs/artifacts h)]
    (if (or (stop-art art) (>= round max-rounds))
      {:rounds round :artifacts art}
      (do (hs/pump! h)
          (hs/poll! h (str "w23-poll-" round))
          (recur (inc round) (hs/artifacts h))))))

;; ------------------------------------------------------------------- positive
(deftest one-submit-reaches-assignments-and-harbor-prepare
  (testing "the declaration is reachable from THIS working directory"
    (is (= (str root "/drivers") (.getCanonicalPath (io/file ".")))
        "this suite documents the drivers/ launch directory of the live driver")
    (is (.canRead (io/file (hs/declaration-root) composition))
        "the harness declaration must resolve independently of the launch directory"))
  (testing "one socket command derives assignments and a Harbor prepare effect"
    (with-redefs [d/log-path scratch-log]
      (let [{:keys [h prepared]} (open-live-session!)
            _ (d/submit! h "Read /app/task.txt and answer it. Two integers: 3 and 4.")
            {:keys [rounds artifacts]} (advance! h #(some #{:harbor/assessed} (kinds % :harbor-in)))]
        (is (some #{:assignment/submitted} (kinds artifacts :assignments))
            (str "assignments stayed empty for " rounds " rounds: " (pr-str (kinds artifacts :assignments))))
        (is (pos? (count (get-in artifacts [:facts :assignments]))))
        (is (seq @prepared) "the Harbor prepare effect never ran")
        (is (= 7 (:expected (first @prepared))) "prepare must derive the expectation from the fixture")
        (is (some #{:harbor/context} (kinds artifacts :harbor-in)))
        (is (some #{:harbor/assessed} (kinds artifacts :harbor-in)))
        (is (some #(and (= :harbor/assessed (:kind %))
                        (map? (:assessment %)) (seq (:assessment %)))
                  (get-in artifacts [:facts :harbor-in]))
            "the framework-owned assessment must land on the harbor port with a real assessment map")
        (is (pos? (count (get-in artifacts [:facts :results]))))
        (is (seq (:socket-receipts artifacts)) "the assignment command must reach the socket")
        (let [closed (hs/close! h)]
          (is (map? closed) "close must report every component")
          (is (every? #(or (= :closed (:status %)) (true? (:cleanup-verified %)))
                      (:components closed)) (pr-str closed)))))))

(deftest declaration-expansion-leaves-no-launch-directory-dependence
  (testing "the compiled declaration carries no working-directory-relative file directive"
    (let [text (hs/declaration-source)
          relative-directives (re-seq #"\((load-lain|load-primitive-environment|load-blocks)\s+\"[^/\"]" text)]
      (is (string? text))
      (is (pos? (count text)))
      (is (empty? relative-directives) (str "unresolved directives: " (pr-str relative-directives)))
      (is (empty? (re-seq #"\(load-lain " text))
          "nested .lain files must be inlined exactly once")
      (is (pos? (count (re-seq #"\(load-primitive-environment \"/" text))))
      (is (every? #(.canRead (io/file %)) (map second (re-seq #"\(load-primitive-environment \"([^\"]+)\"" text)))
          "primitive imports must name readable files"))))

;; ------------------------------------------------------------------- negative
(deftest declaration-load-failure-aborts-initialization
  (testing "failed .lain receipts are declaration failures (the exact live-incident shape)"
    (let [state {:environment/effects
                 {[:environment/load-lain composition 0]
                  {:status :failed
                   :receipt {:status :failed
                             :diagnostics [{:class "java.nio.file.NoSuchFileException"
                                            :message (str root "/drivers/" composition)}]}}}}]
      (is (= [{:kind :environment-effect
               :effect-id [:environment/load-lain composition 0]
               :receipt (get-in state [:environment/effects
                                       [:environment/load-lain composition 0] :receipt])}]
             (hs/declaration-failures nil state))))
    (is (empty? (hs/declaration-failures nil {:environment/effects {[:io/write "x"] {:status :completed}}})))
    (is (= [{:kind :declaration :error {:reason :boom}}]
           (hs/declaration-failures {:error {:reason :boom}} {}))))
  (testing "open! refuses a half session instead of returning a quiescent one"
    (let [rootless (fn [] (hs/open! "lain-w23-a1" {:mode :observe :pi-config d/pi-config-map}))
          relative-resolvable? (.canRead (io/file composition))]
      (with-redefs [hs/declaration-root (constantly nil)]
        (if relative-resolvable?
          (is (some? (rootless)) "a launch directory that CAN see the declaration still works")
          (let [outcome (try (rootless) (catch Throwable t t))]
            (is (instance? Throwable outcome)
                "an unloadable declaration MUST fail initialization, not return a stalled session")
            (is (re-find #"declaration failed to load" (ex-message outcome)))
            (is (some #{:environment-effect} (map :kind (:failures (ex-data outcome)))))
            (is (some #(#{"java.nio.file.NoSuchFileException"} (:class %))
                      (mapcat :diagnostics (keep :receipt (:failures (ex-data outcome)))))
                "the original NoSuchFileException must reach the caller")))))))
