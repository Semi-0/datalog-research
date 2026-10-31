;; W17 focused driver contract tests — PURE functions and in-memory fakes only.
;; NO live Docker / model / container / bridge process / harness session is touched.
;; Covers the 6 driver-review fixes:
;;   1 rpc unmatched-id log frame     2 pump-reader! recur termination
;;   3 eid->wire vector               4 frame-from :assignment-context assoc
;;   5 wire->status uncertain/failed  6 tick-decision exit codes (0/1/2/3)
(ns lain.w17-contract-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json])
  (:import [java.io ByteArrayOutputStream BufferedReader StringReader]
           [java.util.concurrent LinkedBlockingQueue]))

;; load the driver under test by absolute path (not a namespace-path load)
(load-file "/home/lain/experiments/lain-harness-flash-once-20261027/drivers/flash_once.clj")

;; private driver fns via var pointers — only pure ones are referenced
(def rpc #'lain.flash-once/rpc)
(def pump-reader! #'lain.flash-once/pump-reader!)
(def eid->wire #'lain.flash-once/eid->wire)
(def frame-from #'lain.flash-once/frame-from)
(def wire->status #'lain.flash-once/wire->status)
(def tick-decision #'lain.flash-once/tick-decision)

(def log-sink (io/file "/home/lain/experiments/lain-harness-flash-once-20261027/observations/silent-resume-20261029/logs-W17/test-run.jsonl"))

(defn- isolate-log [f]
  ;; log-path is a plain def (not ^:dynamic); swap it for a logs-W17 sink for the duration of
  ;; each test and restore it afterwards, so tests never write to the live logs/flash-once.jsonl
  (when (.exists log-sink) (.delete log-sink))
  (let [orig (var-get #'lain.flash-once/log-path)]
    (alter-var-root #'lain.flash-once/log-path (constantly log-sink))
    (try (f) (finally (alter-var-root #'lain.flash-once/log-path (constantly orig))))))

(use-fixtures :each isolate-log)

(defn- split-concatenated-json [s]
  ;; split a stream of concatenated top-level JSON objects (driver log has no newline separator)
  ;; by brace depth, respecting string literals and escapes; pure string scanning
  (loop [i 0 depth 0 in-str false esc false start 0 acc []]
    (if (>= i (count s))
      acc
      (let [c (.charAt ^String s i)]
        (cond
          esc (recur (inc i) depth in-str false start acc)
          (and in-str (= c \\\\)) (recur (inc i) depth in-str true start acc)
          (= c \") (recur (inc i) depth (not in-str) false start acc)
          in-str (recur (inc i) depth in-str false start acc)
          (or (= c \{) (= c \[)) (recur (inc i) (inc depth) in-str false
                                         (if (zero? depth) i start) acc)
          (or (= c \}) (= c \]))
          (let [d (dec depth)]
            (if (zero? d)
              (recur (inc i) d in-str false start
                     (conj acc (json/parse-string (subs s start (inc i)) true)))
              (recur (inc i) d in-str false start acc)))
          :else (recur (inc i) depth in-str false start acc))))))

(defn- log-events []
  ;; the driver's log is one json/generate-string per event with :append true and NO trailing
  ;; newline -> the .jsonl file is a CONCATENATED json stream; split it by brace depth
  (when (.exists log-sink)
    (split-concatenated-json (slurp log-sink))))

;; ---------------------------------------------------------------- 1. unmatched-id log frame
(deftest rpc-unmatched-id-log-frame
  (testing "rpc skips frames with a non-matching id, logs each as bridge.frame with :unmatched-id
  (the old unresolved symbol `tool` must never resurface), and returns the matching frame"
    (let [q (doto (LinkedBlockingQueue.)
              (.add (json/generate-string {"id" 99 :result {"status" "running" "child" "c-other"}}))
              (.add (json/generate-string {"notification" "tick"}))   ; id-less noise: skipped too
              (.add (json/generate-string {"id" 4 :result {"status" "running"}}))
              (.add (json/generate-string {"id" 1 :result {"status" "completed" "result" "42"}})))
          w (ByteArrayOutputStream.)
          st {:w w :q q :seq (atom 0)}
          out (rpc st {:session "s" :effect-id ["e1"]} 5000)]
      (is (= 1 (get-in out [:frame :id])))
      (is (= "completed" (get-in out [:frame :result :status])))
      (let [logged (log-events)]
        (is (some #(and (= "bridge.frame" (:event %)) (= 99 (:unmatched-id %))) logged)
            "unmatched id 99 logged under bridge.frame with :unmatched-id")
        (is (some #(and (= "bridge.frame" (:event %)) (= 4 (:unmatched-id %))) logged)
            "unmatched id 4 also logged (loop keeps consuming after a skip)")
        (is (every? #(contains? % :unmatched-id) logged)
            "every bridge.frame entry carries :unmatched-id (no unresolved `tool` symbol)")
        (is (= :first-match (if (= 1 (get-in out [:frame :id])) :first-match :nope))))
      ;; the frame actually written to the wire carries the assigned id and wire keys only
      (let [sent (json/parse-string (String. (.toByteArray w)) true)]
        (is (= 1 (:id sent)))
        (is (= "s" (:session sent)))
        (is (= ["e1"] (:effect-id sent)))))))

;; ---------------------------------------------------------------- 2. reader-loop recur termination
(deftest pump-reader-recur-termination
  (testing "pump-reader! forwards EVERY line until EOF (recur, not one-shot) and terminates"
    (let [q (LinkedBlockingQueue.)
          r (BufferedReader. (StringReader. "line-1\nline-2\nline-3\n"))]
      (pump-reader! r q) ; must RETURN at EOF, not stop after one frame and not hang
      (is (= ["line-1" "line-2" "line-3"] (vec (.toArray q))))))
  (testing "immediate EOF terminates at once with an empty queue"
    (let [q (LinkedBlockingQueue.)
          r (BufferedReader. (StringReader. ""))]
      (pump-reader! r q)
      (is (zero? (.size q)))))
  (testing "final line without trailing newline is still forwarded"
    (let [q (LinkedBlockingQueue.)
          r (BufferedReader. (StringReader. "a\nb"))]
      (pump-reader! r q)
      (is (= ["a" "b"] (vec (.toArray q)))))))

;; ---------------------------------------------------------------- 3. eid->wire vector
(deftest eid->wire-vector
  (testing "vector effect ids are preserved (sequential?, not seq?)"
    (is (= ["a1" "x"] (eid->wire ["a1" :x])))
    (is (vector? (eid->wire ["a1" :x])))
    (is (= ["a1"] (eid->wire ["a1"])))
    (is (= ["7" "k"] (eid->wire [7 :k])))
    (is (nil? (eid->wire "not-a-seq")) "bare string eid fails to wire (nil)")
    (is (nil? (eid->wire nil)))))

;; ---------------------------------------------------------------- 4. frame-from :assignment-context
(deftest frame-from-assignment-context
  (testing "ctx :assignment-context is SELECTED FROM THE CONTEXT and assoc'd onto the wire;
  junk keys dropped; never {} when ctx carries it; key absent when ctx does not"
    (let [ctx {:session "s" :effect-id ["e1"]
               :assignment-context {:instructions "do it" :trial-id "t7" :secret-leak 42}}
          wire (frame-from ctx)]
      (is (contains? wire :assignment-context))
      (is (= {:instructions "do it" :trial-id "t7"} (:assignment-context wire))))
    (let [bare (frame-from {:session "s" :effect-id ["e1"]})]
      (is (not (contains? bare :assignment-context)) "absent in ctx -> key absent, not {}")
      (is (= ["e1"] (:effect-id bare)))))
  (testing "tool/operation/launch-effect-id wire rules"
    (let [t (frame-from {:session "s" :effect-id ["e"] :tool "read" :arguments {:path "/x"}})]
      (is (= "read" (:tool t)))
      (is (= {:path "/x"} (:arguments t)))
      (is (nil? (:operation t))))
    (let [rc (frame-from {:session "s" :effect-id ["e"] :operation "reconcile"
                          :launch-effect-id ["L1" :n]})]
      (is (= "reconcile" (:operation rc)))
      (is (= ["L1" "n"] (:launch-effect-id rc)))
      (is (not (contains? rc :tool)) "reconcile frame carries no tool"))))

;; ---------------------------------------------------------------- 5. wire->status uncertain vs failed
(deftest wire->status-uncertain-vs-failed
  (testing "closed 7-status vocabulary maps through"
    (is (= :completed (:status (wire->status {:result {:status "COMPLETED" :result "42" :child "c1"}}))))
    (is (= "c1" (:child (wire->status {:result {:status "running" :child "c1"}}))))
    (is (= :launched (:status (wire->status {:result {:status "launched" :child "c"}}))))
    (is (= :reconciled (:status (wire->status {:result {:status "reconciled"}}))))
    (is (true? (:cleanup-verified (wire->status {:result {:status "stopped" :cleanup-verified true :child "c"}}))))
    (is (= :failed (:status (wire->status {:result {:status "failed" :failure {:message "m"}}}))))
    (is (= :uncertain (:status (wire->status {:result {:status "uncertain"}})))))
  (testing "unknown shape, plain frame (collect/stop semantics) -> :failed (fail closed)"
    (let [r (wire->status {:result {:status "weird"}})]
      (is (= :failed (:status r)))
      (is (re-find #"unrecognized" (get-in r [:failure :message])))))
  (testing "malformed/absent reply AFTER a launch frame was invoked -> :uncertain, never :failed"
    (let [launch-req {:tool "subagent_spawn_batch" :arguments {}}]
      (doseq [bad-frame [{} {:error "boom"} {:raw "garbage"} {:result {:status "weird"}}]]
        (let [r (wire->status bad-frame launch-req)]
          (is (= :uncertain (:status r)) (str "frame " (pr-str bad-frame) " after invoked launch"))
          (is (re-find #"never relaunch" (get-in r [:failure :message])))))))
  (testing "malformed reply AFTER a reconcile frame -> :uncertain (W7 reconcile-never-relaunch)"
    (let [rec-req {:operation "reconcile" :launch-effect-id ["L1"]}]
      (doseq [bad-frame [{} {:error "timeout"}]]
        (let [r (wire->status bad-frame rec-req)]
          (is (= :uncertain (:status r)))
          (is (re-find #"launch-effect-id" (get-in r [:failure :message])))))
      ;; the reconcile REQUEST is never a relaunch: operation reconcile + durable launch-effect-id,
      ;; no tool/spawn crosses the wire
      (let [wire (frame-from (assoc rec-req :session "s" :effect-id ["e"]))]
        (is (= "reconcile" (:operation wire)))
        (is (nil? (:tool wire)))
        (is (= ["L1"] (:launch-effect-id wire))))))
  (testing "non-launch requests keep the old meaning: unknown shape -> :failed"
    (is (= :failed (:status (wire->status {:result {:status "weird"}} {:tool "read"}))))
    (is (= :failed (:status (wire->status {:error "x"} {:operation "dispose"}))))))

;; ---------------------------------------------------------------- 6. tick-decision exit codes
(deftest tick-decision-exit-codes
  (testing "exit 0: only a framework assessment finishes with 0"
    (is (= {:action :finish :exit 0 :reason :assessed}
           (tick-decision {:pump-error nil :poll-error nil :assessed? true
                           :elapsed-ms 10 :guard-ms 1200000}))))
  (testing "exit 1: any pump/poll EXCEPTION stops the run NONZERO"
    (doseq [k [:pump-error :poll-error]]
      (let [d (tick-decision {:pump-error (when (= k :pump-error) "boom")
                              :poll-error (when (= k :poll-error) "boom")
                              :assessed? false :elapsed-ms 10 :guard-ms 1200000})]
        (is (= :stop (:action d)) k)
        (is (= 1 (:exit d)) k))))
  (testing "exit 3: the wall-clock guard only STOPS and its stop is nonzero, never a silent exit 0"
    (let [d (tick-decision {:pump-error nil :poll-error nil :assessed? false
                            :elapsed-ms 1200001 :guard-ms 1200000})]
      (is (= :stop (:action d)))
      (is (= 3 (:exit d)))
      (is (= :guard-timeout (:reason d)))))
  (testing "otherwise: recur with no exit"
    (let [d (tick-decision {:pump-error nil :poll-error nil :assessed? false
                            :elapsed-ms 5 :guard-ms 1200000})]
      (is (= :recur (:action d)))
      (is (nil? (:exit d)))))
  (testing "precedence: error > assessed > guard"
    (is (= 1 (:exit (tick-decision {:pump-error "e" :poll-error nil :assessed? true
                                    :elapsed-ms 9e9 :guard-ms 1}))))
    (is (= 0 (:exit (tick-decision {:pump-error nil :poll-error nil :assessed? true
                                    :elapsed-ms 9e9 :guard-ms 1})))))
  (testing "tick itself never emits exit 2; 2 belongs to the -main memory-abort path, which exists
  guarded by memory-ok?; and -main :stop exits with the decision's nonzero code"
    (doseq [pe [nil "e"] ae [true false] el [0 9e9] gm [1200000 1]]
      (let [d (tick-decision {:pump-error pe :poll-error nil :assessed? ae
                              :elapsed-ms el :guard-ms gm})]
        (is (not= 2 (:exit d)))))
    (let [src (slurp "/home/lain/experiments/lain-harness-flash-once-20261027/drivers/flash_once.clj")]
      (is (re-find #"(if-not \(memory-ok\?\)[\s\S]{0,140}\(System/exit 2\))" src)
          "-main aborts with exit 2 when the memory guard fails")
      (is (re-find #"\(System/exit \(:exit decision\)\)" src)
          "-main :stop exits with the decision's nonzero code (1 or 3)"))))

;; ---------------------------------------------------------------- statuses vocabulary
(deftest statuses-vocabulary
  (is (= #{:launched :reconciled :completed :stopped :uncertain :failed :running}
         lain.flash-once/statuses)))
