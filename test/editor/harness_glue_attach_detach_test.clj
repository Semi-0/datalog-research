(ns editor.harness-glue-attach-detach-test
  "Package 4: the editor observes/attaches/detaches a MAIN session it does NOT own.
  The MAIN session here is a stand-in owned by someone else: an atom plus its own order clock,
  a disposal flag and a log of the ownership operations its owner performs. Every session
  operation the editor asks for goes through an injected fake RPC/extension, so the recorded
  call sequence is the assertion: attach must only subscribe, detach must only unsubscribe,
  and no editor path may close, dispose, kill or clock the observed session.
  No live model, container, Docker, socket or network is involved."
  (:require [clojure.test :refer [deftest is testing]]
            [graph.lain-editor.harness-glue :as glue]))

;;; ---------------------------------------------------------------- fake MAIN

(defn projection [state]
  "What an outside observer may legitimately see: counters, not nets or FactLogs."
  {:tick (:next-order state)
   :clocks (vec (sort-by pr-str (keys (:clock/subscriptions state))))
   :extensions (vec (sort-by pr-str (keys (:session/extensions state))))})

(defn main-session
  "A MAIN session with its own owner: `owner-tick!` is the only thing that advances it."
  [identity]
  {:identity identity
   :session (atom {:next-order 0
                   :runtime/commit-tick 0
                   :program/epoch 0
                   :session/extensions {:harness/fake {}}
                   :clock/subscriptions {}
                   :traces {}
                   :runtime/errors []})
   :closed (atom nil)
   :lifetime (atom [])
   :calls (atom [])})

(defn owner-tick!
  "The owner's own progress and clock work; the editor never calls this."
  ([main] (owner-tick! main 1))
  ([main steps]
   (swap! (:session main)
          (fn [state]
            (assoc-in (update state :next-order + steps)
                      [:clock/subscriptions (str "owner-" (rand-int 1000000))]
                      {:owner :main})))
   @(:session main)))

(defn owner-close! [main]
  (reset! (:closed main) {:outcome {:status :closed}})
  (swap! (:lifetime main) conj :close))

(defn- record! [main op target]
  (swap! (:calls main) conj [op (:identity target)])
  main)

(defn- refusing [main op]
  "Ownership endpoints: recorded, and they blow up so any editor-side misuse is loud."
  (fn [& _]
    (swap! (:lifetime main) conj op)
    (throw (ex-info "the editor must not own the MAIN session" {:op op}))))

(defn fake-rpc [main]
  "Injected session RPC/extension. Observation endpoints are implemented with the same
  primitive the real boundary uses (an atom watch); ownership endpoints exist only to prove
  they are never reached from an editor path."
  {:subscribe (fn [target sink]
                (record! main :subscribe target)
                (add-watch (:session target) ::fake
                           (fn [_ _ _ state] (sink (projection state))))
                (sink (projection @(:session target)))
                {:subscription ::fake :transport :fake-rpc})
   :unsubscribe (fn [target subscription]
                  (record! main :unsubscribe target)
                  (remove-watch (:session target) subscription)
                  {:status :unsubscribed})
   :snapshot (fn [target] (record! main :snapshot target) (projection @(:session target)))
   :close (refusing main :close)
   :close-session (refusing main :close-session)
   :dispose (refusing main :dispose)
   :kill (refusing main :kill)
   :stop (refusing main :stop)
   :pump (refusing main :pump)
   :stop-clocks (refusing main :stop-clocks)
   :schedule-clocks (refusing main :schedule-clocks)
   :start-timer (refusing main :start-timer)
   :stop-timer (refusing main :stop-timer)
   :install-extension (refusing main :install-extension)
   :mutate (refusing main :mutate)})

(defn ops [main] (mapv first @(:calls main)))
(defn observed [main] (glue/observation main))
(defn seen [main] (:seen (observed main)))

;;; --------------------------------------------------------------------- tests

(defn with-fake
  "Runs body with the fake boundary installed; always releases the editor subscription."
  [identity body]
  (let [main (main-session identity)]
    (binding [glue/*rpc* (fake-rpc main)]
      (try (body main)
           (finally (glue/detach! main))))))

(deftest attach-subscribes-and-observes-only
  (with-fake "attach-observe"
    (fn [main]
      (let [before @(:session main)
            attachment (glue/attach! main)]
        (is (:subscribed attachment) "attach reports a live subscription")
        (is (true? (:session-lived attachment)) "the MAIN session was already alive")
        (is (false? (:already-attached attachment)))
        (is (false? (:clocks-owned-by-editor attachment))
            "the returned view states plainly that the editor owns no clocks")
        (is (= #{:subscribe :snapshot} (set (ops main)))
            "attach performs subscription and a read, nothing else")
        (is (= [] @(:lifetime main)) "no lifetime/clock operation was attempted")
        (is (nil? @(:closed main)) "attach did not dispose anything")
        (is (= before @(:session main)) "attach did not touch the MAIN session state")
        (is (= [] (:clocks (:observation attachment)))
            "the MAIN session had no editor-scheduled clocks")
        (is (glue/attached? main) "the session is registered as observed")))))

(deftest attach-is-idempotent
  (with-fake "attach-idempotent"
    (fn [main]
      (let [first (glue/attach! main)
            second (glue/attach! main)
            third (glue/attach! main)]
        (is (false? (:already-attached first)))
        (is (true? (:already-attached second)) "repeat attach is recognized")
        (is (true? (:already-attached third)))
        (is (= (:subscription first) (:subscription second) (:subscription third))
            "one subscription is reused, never stacked")
        (is (= 3 (:subscribers third)))
        (is (= [:subscribe :snapshot] (ops main))
            "idempotence must not multiply subscribe calls")
        (owner-tick! main 2)
        (is (= 2 (:tick (:observation (observed main))))
            "one watch delivers the session's progress exactly once per mutation")))))

(deftest concurrent-attach-claims-one-subscription
  (with-fake "attach-concurrent"
    (fn [main]
      (let [results (mapv deref (for [_ (range 8)] (future (glue/attach! main))))]
        (is (= 1 (count (filter (complement :already-attached) results))))
        (is (= 8 (:subscribers (last (sort-by :subscribers results)))))
        (is (= 1 (count (filter #{:subscribe} (ops main)))))
        (is (= (distinct (map :subscription results)) (list (:subscription (first results))))
            "every concurrent attacher received the same subscription")))))

(deftest detach-unsubscribes-on-last-reference-only
  (with-fake "detach-refcount"
    (fn [main]
      (glue/attach! main)
      (glue/attach! main)
      (let [first (glue/detach! main)]
        (is (false? (:released first)) "one of two references dropped")
        (is (true? (:already-detached first)) "the subscription itself is still live")
        (is (true? (:live (glue/observation main)))
            "still observing after a partial detach")
        (is (true? (:session-lived first))))
      (owner-tick! main 1)
      (let [last (glue/detach! main)]
        (is (true? (:released last)) "the last reference unsubscribed")
        (is (true? (:session-lived last)) "and the MAIN session survived it")
        (is (false? (glue/attached? main))))
      (is (= [:subscribe :snapshot :unsubscribe] (ops main)))
      (let [seen-now (seen main)]
        (owner-tick! main 5)
        (is (= seen-now (seen main)) "no observation is delivered after the last detach")
        (is (= 6 (:next-order @(:session main))) "the owner kept ticking the session"))
      (let [extra (glue/detach! main)]
        (is (true? (:already-detached extra)) "detaching twice is a no-op")
        (is (true? (:session-lived extra)) "and still does not kill anything")
        (is (= [:subscribe :snapshot :unsubscribe] (ops main))
            "no second unsubscribe call reaches the boundary")))))

(deftest editor-disconnect-leaves-the-main-session-alive
  (with-fake "disconnect-alive"
    (fn [main]
      (glue/attach! main)
      (owner-tick! main 3)
      (is (= 3 (:tick (:observation (observed main)))))
      (glue/detach! main)
      (testing "the owner keeps running a session the editor walked away from"
        (owner-tick! main 4)
        (is (= 7 (:next-order @(:session main))))
        (is (nil? @(:closed main)) "the editor never closed or disposed it")
        (is (empty? (some (into #{} glue/lifetime-ops) (map first @(:calls main))))
            "no ownership operation was ever attempted from the editor")
        (is (= 2 (count (keys (:clock/subscriptions @(:session main)))))
            "the ticks above are owner-owned clocks; the editor scheduled none of them"))
      (testing "the editor's own view is empty and harmless after disconnect"
        (let [stale (glue/observation main)]
          (is (false? (:live stale)))
          (is (nil? (:observation stale)))
          (is (= [] (:observations stale))))
        (is (= [] (glue/subscriptions-snapshot)))))))

(deftest no-editor-path-may-own-lifetime-or-clocks
  (with-fake "lifetime-gate"
    (fn [main]
      (let [target {:identity "lifetime-gate" :session (:session main) :handle main}
            clocks (-> @(:session main) :clock/subscriptions keys count)]
        (doseq [op (sort-by pr-str glue/lifetime-ops)]
          (is (try (do (#'glue/rpc op target) false)
                   (catch Throwable error
                     (boolean (re-find #"may not own the MAIN session" (ex-message error)))))
              (str op " is refused before it reaches the session boundary")))
        (is (try (do (#'glue/rpc :frobnicate target) false)
                 (catch Throwable error
                   (boolean (re-find #"Unknown session observation operation"
                                     (ex-message error)))))
            "unknown operations are refused too, not silently ignored")
        (is (= [] @(:lifetime main)) "nothing ownership-shaped was even attempted")
        (is (= clocks (count (keys (:clock/subscriptions @(:session main)))))
            "no clock was scheduled or stopped by the editor")
        (is (= #{:subscribe :unsubscribe :snapshot} glue/observation-ops)
            "the allowlist is exactly subscribe/observe")
        (is (every? (complement glue/lifetime-ops) glue/observation-ops)
            "the two vocabularies never overlap")))))

(deftest with-observation-releases-even-when-the-body-fails
  (let [main (main-session "with-observation")]
    (binding [glue/*rpc* (fake-rpc main)]
      (is (thrown? IllegalStateException (glue/with-observation main #(throw (IllegalStateException. "pane died")))))
      (is (= [:subscribe :snapshot :unsubscribe] (ops main))
          "a failing editor view still leaves only a subscription and its release")
      (is (nil? @(:closed main)))
      (is (false? (glue/attached? main)))
      (owner-tick! main 2)
      (is (= 2 (:next-order @(:session main))) "the MAIN session outlives the failed view"))))

(deftest observations-are-a-bounded-projection
  (with-fake "bounded-projection"
    (fn [main]
      (glue/attach! main)
      (dotimes [step 80] (owner-tick! main))
      (let [view (observed main)]
        (is (= 81 (:seen view)) "80 owner ticks plus the subscription's initial snapshot")
        (is (<= (count (:observations view)) glue/observation-window)
            "the editor tail is bounded, so a long-lived tab cannot grow without limit")
        (is (= (range 1 81) (map :tick (:observations view))) "newest first, no duplicates")
        (is (every? #(= #{:tick :clocks :extensions} (set (keys %))) (:observations view))
            "no net, handler registry or FactLog crosses the observer boundary")
        (is (not (instance? clojure.lang.IDeref (:observation view)))
            "snapshots are plain data, not live session handles")))))

(deftest default-boundary-watches-an-atom-session-without-injection
  (testing "no fake, no RPC: the glue's own watch-based boundary"
    (let [session (atom {:next-order 0 :clock/subscriptions {} :session/extensions {}
                         :traces {} :runtime/errors []})]
      (let [attachment (glue/attach! session)]
        (is (true? (:subscribed attachment)))
        (is (= :session-watch (:transport attachment)))
        (swap! session update :next-order inc)
        (is (= 1 (:tick (:observation (glue/observation session)))))
        (is (= 0 (count (:clocks (:observation (glue/observation session)))))))
      (is (true? (:released (glue/detach! session))))
      (let [after @session]
        (swap! session update :next-order inc)
        (is (false? (:live (glue/observation session))))
        (is (= (inc (:next-order after)) (:next-order @session))
            "the session kept its state; only the observer went away")))))

(deftest detach-after-the-owner-closed-reports-the-truth
  (with-fake "closed-by-owner"
    (fn [main]
      (glue/attach! main)
      (owner-close! main)
      (let [detached (glue/detach! main)]
        (is (true? (:released detached)))
        (is (false? (:session-lived detached))
            "the editor reports the owner's disposal instead of pretending otherwise")
        (is (= [:close] @(:lifetime main)) "only the owner closed it, once")
        (is (= [:subscribe :snapshot :unsubscribe] (ops main)))))))

(deftest two-distinct-sessions-with-one-owner-chosen-identity
  (testing "identity strings belong to the owner; the observer keys on the session itself"
    (let [a (main-session "shared-id")
          b (main-session "shared-id")]
      (binding [glue/*rpc* (fake-rpc a)] (glue/attach! a))
      (is (true? (glue/attached? a)))
      (binding [glue/*rpc* (fake-rpc b)] (glue/attach! b))
      (is (true? (glue/attached? b)))
      (is (= 2 (count (glue/subscriptions-snapshot))))
      (let [detached (binding [glue/*rpc* (fake-rpc b)] (glue/detach! b))]
        (is (true? (:released detached)))
        (is (true? (glue/attached? a)) "the other session keeps being observed")
        (is (= 1 (count (glue/subscriptions-snapshot))))
        (glue/detach! a)))))
