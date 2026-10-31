(ns propagators.compiler-2-pi-transport-test
  "W1: how a completed Pi child result reaches the main session as an assignment result.
  Proves the acknowledged-child requirement empirically against the public transport API:
  a launch response without :child can never yield poll observations; launch + :child plus a
  completion poll yields exactly one normalized result at pi:derive-completions; and an
  ambiguous (or unknown) launch response never becomes success; post-invocation launch
  ambiguity stays :uncertain and must be reconciled, never relaunched (contract §4)."
  (:require [clojure.test :refer [deftest is testing]]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.pi-extension :as pi]
            [examples.lain.subagents.pi-transport :as transport]))

(def config {:plugin :pi-subagents :version "0.81.0" :provider "ollama"
             :model "explicit-local-model" :cwd "/tmp/lain-pi-fixture"
             :tools ["read" "write"]})
(def session "s")
(def assignment "a")
(def trial (pi/trial-id session assignment))

(defn launch-request [order]
  {:session trial :id [trial :launch] :backend :pi-subagents
   :operation :launch :task "Solve fixture" :context {} :order order})

(defn make-adapter [responses]
  "Synthetic transport; responses are consumed in call order (:subagents_enable is served
  without consuming the script). A Throwable element is thrown from the RPC."
  (let [script (atom responses)]
    (transport/open! session config
      {:mode :synthetic
       :call! (fn [ctx]
                (if (= "subagents_enable" (:tool ctx))
                  {:cleanup-verified true}
                  (let [response (first @script)]
                    (swap! script (fn [s] (rest s)))
                    (if (instance? Throwable response) (throw response) response))))
       :reconcile! (fn [_] {:status :uncertain :failure {:message "reconciliation unavailable"}})
       :dispose! (fn [_] {:cleanup-verified true})})))

(defn observation-facts [observations]
  (facts/from-facts session (map #(pi/observation-fact session assignment %) observations)))

(defn claim-log [observations]
  (facts/from-facts session
    (into [(pi/requested-fact session assignment 1 (launch-request 1))]
          (map #(pi/observation-fact session assignment %) observations))))

(defn make-logged-adapter
  "Synthetic transport that also records every RPC the adapter makes on the public
  injection hooks. Launch frames are identifiable (:tool \"subagent\" with :async true);
  reconciliation runs on the separate :reconcile! hook and carries the ORIGINAL launch
  effect-id. An exhausted reconcile script answers the contract §4 signal-2 reply."
  [responses reconcile-responses]
  (let [script (atom responses)
        reconcile-script (atom reconcile-responses)
        rpcs (atom [])]
    {:rpcs rpcs
     :adapter (transport/open! session config
                {:mode :synthetic
                 :call! (fn [ctx]
                          (if (= "subagents_enable" (:tool ctx))
                            (do (swap! rpcs conj {:hook :activate}) {:cleanup-verified true})
                            (do (swap! rpcs conj {:hook :call :tool (:tool ctx) :arguments (:arguments ctx)})
                                (let [response (first @script)]
                                  (swap! script (fn [s] (rest s)))
                                  (if (instance? Throwable response) (throw response) response)))))
                 :reconcile! (fn [ctx]
                               (swap! rpcs conj {:hook :reconcile
                                                 :launch-effect-id (:launch-effect-id ctx)})
                               (let [response (first @reconcile-script)]
                                 (swap! reconcile-script (fn [s] (rest s)))
                                 (or response {:status :uncertain
                                               :failure {:message
                                                        "No unique durable launch receipt; never relaunch"}})))
                 :dispose! (fn [_] {:cleanup-verified true})})}))

(defn launch-rpcs [rpcs]
  (filter #(and (= :call (:hook %)) (= "subagent" (:tool %))
                (true? (get-in % [:arguments :async]))) @rpcs))

(defn derived-requests [observations]
  (map :request (facts/ordered-facts
                  (pi/derive-execution-requests session config nil observations))))

(deftest launch-without-child-never-yields-poll-observations
  (let [adapter (make-adapter [{:status :launched}])
        _ (testing "poll before any launch fails closed, it is not an empty poll"
            (is (thrown? clojure.lang.ExceptionInfo
                         (transport/poll! adapter assignment "p0" 1))))
        launched (transport/dispatch! adapter assignment (launch-request 1))]
    (testing "the public launch path itself accepts an ack without :child"
      (is (= [:launched] (map :kind launched)))
      (is (nil? (:child (first launched)))))
    (testing "polling is where the acknowledged-child contract is rejected"
      (let [error (try (transport/poll! adapter assignment "p1" 2)
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo error))
        (is (re-find #"acknowledged owned child" (.getMessage error)))))
    (testing "the rejection is stable, nothing is cached as an empty poll"
      (is (thrown? clojure.lang.ExceptionInfo (transport/poll! adapter assignment "p1" 3))))
    (testing "the childless launch receipt cannot normalize into runtime facts either"
      (is (thrown? clojure.lang.ExceptionInfo
                   (pi/normalize-observations session (observation-facts launched)))))
    (is (:cleanup-verified (transport/close! adapter)))))

(deftest acknowledged-child-plus-completion-yields-exactly-one-result
  (let [adapter (make-adapter [{:status :launched :child "child-a"}
                               {:status :completed :child "child-a" :result {:text "done"}}])
        launched (first (transport/dispatch! adapter assignment (launch-request 1)))
        completed (transport/poll! adapter assignment "p1" 2)]
    (is (= :launched (:kind launched)))
    (is (= "child-a" (:child launched)))
    (is (= [:completed] (map :kind completed)))
    (testing "one poll identity returns the cached observation, never a re-invocation"
      (is (= completed (transport/poll! adapter assignment "p1" 3))))
    (let [log (claim-log (concat [launched] completed))
          results (facts/ordered-facts (pi/derive-completions session log))]
      (testing "exactly one normalized result reaches pi:derive-completions"
        (is (= 1 (count results)))
        (is (= :runtime/completed (:kind (first results))))
        (is (= {:text "done"} (:result (first results))))
        (is (= "child-a" (:child (first results)))))
      (testing "the same result is stable across re-derivation (no duplicates)"
        (is (= results (facts/ordered-facts (pi/derive-completions session log))))))
    (is (:cleanup-verified (transport/close! adapter)))))

(deftest ambiguous-response-stays-ambiguous-never-success
  (testing "lost launch ack: external delivery is unknown, so the launch stays :uncertain (reconcile path), never success"
    (let [adapter (make-adapter [(RuntimeException. "ack lost")])
          launched (transport/dispatch! adapter assignment (launch-request 1))]
      (is (= :uncertain (:kind (first launched))))
      (is (nil? (:child (first launched))))
      (is (thrown? clojure.lang.ExceptionInfo (transport/poll! adapter assignment "p1" 2)))
      (let [log (claim-log [(first launched)])]
        (is (= :runtime/uncertain (:kind (last (facts/ordered-facts
                                                 (pi/normalize-observations session log))))))
        (is (empty? (facts/ordered-facts (pi/derive-completions session log)))))))
  (testing "an ambiguous poll response with an acknowledged child stays uncertain"
    (let [adapter (make-adapter [{:status :launched :child "child-a"}
                                 {:status :uncertain :failure {:message "status unreadable"}}])
          launched (first (transport/dispatch! adapter assignment (launch-request 1)))
          polled (transport/poll! adapter assignment "p1" 2)]
      (is (= :uncertain (:kind (first polled))))
      (is (nil? (:child (first polled))))
      (is (not (contains? (first polled) :result)))
      (let [log (claim-log (concat [launched] polled))]
        (is (empty? (facts/ordered-facts (pi/derive-completions session log))))
        (is (= :runtime/uncertain
               (:kind (last (facts/ordered-facts (pi/derive-status session log)))))))))
  (testing "unknown status: post-invocation launch ambiguity routes to the reconcile
  path (never success, never a quiet :failed), while the poll path fails closed to :failed"
    ;; The reply arrived but was rejected by bounded-response after the spawn frame may
    ;; already have been delivered (pi_transport.clj:16-24); the boundary's post-invocation
    ;; catch keeps the LAUNCH path ambiguous (boundary.clj:49-52) because external delivery
    ;; cannot be ruled out. The mandated follow-up is reconcile, never relaunch
    ;; (contract.md §4) - pinned end to end in
    ;; unknown-launch-status-lands-on-the-reconcile-path-never-a-relaunch.
    (let [launch-adapter (make-adapter [{:status :done}])]
      (is (= :uncertain (:kind (first (transport/dispatch! launch-adapter assignment
                                                           (launch-request 1)))))))
    (let [adapter (make-adapter [{:status :launched :child "child-a"} {:status :done}])]
      (transport/dispatch! adapter assignment (launch-request 1))
      (is (= :failed (:kind (first (transport/poll! adapter assignment "p1" 2))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (transport/dispatch! (make-adapter [{:status :done}]) assignment
                                      (assoc (launch-request 1) :backend :other))))))

(deftest unknown-launch-status-lands-on-the-reconcile-path-never-a-relaunch
  "A malformed or uncertain launch reply is post-invocation ambiguity: the spawn frame
  may already have been delivered externally, so the boundary keeps it :uncertain instead
  of :failed (correct per the supervisor ruling; contract.md §4). The contract for that
  path is reconcile-never-relaunch: no started/completed/progress fact is produced, the
  derived next operation is :reconcile carrying the ORIGINAL launch effect-id, the
  scripted RPC shows exactly one launch frame and one reconcile frame and never a second
  :launch, and both the explicit :uncertain reply and the unknown-status rejection land
  on :runtime/uncertain. The poll-path unknown status is a different path and stays :failed."
  (testing "unknown-status launch reply that reconciles to a child: one launch, one reconcile, no relaunch"
    (let [a (make-logged-adapter [{:status :done}] [{:status :reconciled :child "child-a"}])
          launched (first (transport/dispatch! (:adapter a) assignment (launch-request 1)))]
      (is (= :uncertain (:kind launched)))
      (is (nil? (:child launched)))
      (let [claims (claim-log [launched])]
        (testing "no started/completed/progress success fact is ever produced for the rejected launch"
          (is (= #{:runtime/requested :runtime/uncertain}
                 (set (map :kind (facts/ordered-facts (pi/derive-status session claims))))))
          (is (empty? (facts/ordered-facts (pi/derive-completions session claims)))))
        (testing "the derived next operation after the uncertain fact is :reconcile, never a relaunch"
          (is (= [:reconcile] (map :operation (derived-requests claims)))))
        (let [reconciled (first (transport/dispatch! (:adapter a) assignment
                                (assoc (first (derived-requests claims)) :order 2)))]
          (is (= :reconciled (:kind reconciled)))
          (is (= "child-a" (:child reconciled)))
          (let [claims2 (claim-log [launched reconciled])]
            (is (= #{:runtime/requested :runtime/uncertain :runtime/reconciled}
                   (set (map :kind (facts/ordered-facts (pi/derive-status session claims2))))))
            (testing "a reconciled child moves the FSM to :collect - still never a second :launch"
              (is (= #{:collect} (set (map :operation (derived-requests claims2)))))
              (is (empty? (facts/ordered-facts (pi/derive-completions session claims2))))))))
      (testing "the scripted RPC shows one launch frame and one reconcile with the ORIGINAL launch effect-id"
        (is (= 1 (count (launch-rpcs (:rpcs a)))))
        (is (= [{:hook :reconcile :launch-effect-id [trial :launch]}]
               (filter #(= :reconcile (:hook %)) @(:rpcs a))))
        (is (every? #(not= :launch (:hook %)) @(:rpcs a))))
      (is (:cleanup-verified (transport/close! (:adapter a))))))
  (testing "explicit uncertain launch reply whose reconcile stays uncertain: permanent reconcile, never relaunch"
    (let [a (make-logged-adapter [{:status :uncertain :failure {:message "launch ack unreadable"}}] [])
          launched (first (transport/dispatch! (:adapter a) assignment (launch-request 1)))]
      (is (= :uncertain (:kind launched)))
      (let [claims (claim-log [launched])]
        (is (= #{:runtime/requested :runtime/uncertain}
               (set (map :kind (facts/ordered-facts (pi/derive-status session claims))))))
        (is (= #{:runtime/requested :runtime/uncertain}
               (set (map :kind (facts/ordered-facts
                                 (pi/normalize-observations session claims))))))
        (is (= [:reconcile] (map :operation (derived-requests claims))))
        (let [again (first (transport/dispatch! (:adapter a) assignment
                              (assoc (first (derived-requests claims)) :order 2)))]
          (is (= :uncertain (:kind again)))
          (let [claims2 (claim-log [launched again])]
            (testing "reconcile-uncertain keeps the trial on :runtime/uncertain with the reconcile op armed, never a relaunch"
              (is (= #{:runtime/requested :runtime/uncertain}
                     (set (map :kind (facts/ordered-facts (pi/derive-status session claims2))))))
              (is (= #{:reconcile} (set (map :operation (derived-requests claims2)))))
              (is (empty? (facts/ordered-facts (pi/derive-completions session claims2))))))))
      (testing "exactly one launch frame was ever sent; reconcile ran on its own hook"
        (is (= 1 (count (launch-rpcs (:rpcs a)))))
        (is (= 1 (count (filter #(= :reconcile (:hook %)) @(:rpcs a))))))
      (is (:cleanup-verified (transport/close! (:adapter a)))))))
