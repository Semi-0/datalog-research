(ns graph.lain-editor.runtime.managed-session-test
  "Controlled real-API acceptance; no compiler or lifecycle overrides."
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.server :as server]
            [graph.lain-editor.integration.editor-test :as fixture]
            [propagators.compiler-2.session :as compiler]
            [propagators.compiler-2.session.host :as host]
            [propagators.compiler-2.session.dependencies :as dependency]
            [propagators.compiler-2.session.operators :as operators]
            [propagators.cells.value :as value]
            [propagators.experimental.visualization.data :as data]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.high-level :as high-level]
            [propagators.stdlib.source-publication :as publication]))

(defn await! [predicate]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (if (predicate) true
          (if (> (System/nanoTime) deadline)
            (throw (ex-info "Timed out waiting for controlled callback" {}))
            (do (Thread/sleep 5) (recur)))))))

(defn boundary [views id]
  (host/boundary-for (:session (get @(editor/owner-catalog views) id))))

(deftest headless-views-refresh-and-stale-fences
  (let [views (atom {}) cmd #(server/command! views %)]
    (try
      (let [opened (cmd {:op "open-session" :source "(define input) (define result (+ input 1))"})
            id (:sessionId opened) owner (boundary views id)]
        (is (empty? @views))
        (is (every? string? (map opened [:sessionId :runId :generation :lifecycle])))
        (is (true? (:compilerSession opened)))
        (is (= "active" (:lifecycle opened)))
        (host/submit! owner (compiler/cell-input (compiler/binding-cell (host/current-session owner) 'input) 10))
        (await! #(= 11 (compiler/binding-value (host/current-session owner) 'result)))
        (is (= "11" (:value (cmd {:op "inspect" :sessionId id :generation (:generation opened)
                                  :display fixture/display}))))
        (is (some #(= "result" (:label %)) (:candidates (cmd {:op "complete" :sessionId id :generation (:generation opened)}))))
        (let [extended (cmd {:op "extend" :sessionId id :generation (:generation opened) :source "(define later (+ result 1))"})
              before (host/current-session owner)]
          (is (= 12 (compiler/binding-value before 'later)))
          (is (= id (:sessionId extended)))
          (is (= (:runId opened) (:runId extended)))
          (is (not= (:generation opened) (:generation extended)))
          (is (thrown? Exception (cmd {:op "extend" :sessionId id :generation (:generation opened) :source "(define stale 1)"})))
          (is (= (compiler/network before) (compiler/network (host/current-session owner))))
          (cmd {:op "reopen-session" :sessionId id :pane "a"})
          (cmd {:op "reopen-session" :sessionId id :pane "b"})
          (cmd {:op "close" :pane "a"})
          (is (= "active" (name (host/lifecycle owner))))
          (is (= (compiler/network before) (compiler/network (host/current-session owner))))
          (is (= [id] (mapv :sessionId (:sessions (cmd {:op "list-sessions"})))))
          (let [document (fixture/document (assoc (fixture/pane "a" "unrun draft") :executedSource "old")
                                           (fixture/pane "b" "unrun second"))
                restored (cmd {:op "restore" :workspace document
                               :reconnect {"a" (select-keys opened [:sessionId :runId :generation])}})]
            (is (= (:generation extended) (get-in restored [:generations "a"])))
            (is (= (:runId opened) (get-in restored [:runIds "a"])))
            (is (= (:source opened) (get-in restored [:sessions "a" :source])))
            (is (= (compiler/network before) (compiler/network (host/current-session owner))))
            (is (= "unrun draft" (get-in document [:panes 0 :source])))
            (is (= {} (:generations (cmd {:op "restore" :workspace document})))))
          (is (= {:stopped id} (cmd {:op "stop-session" :sessionId id :generation (:generation extended)})))
          (is (= :closed (host/lifecycle owner)))
          (is (empty? @views))
          (is (thrown? Exception (cmd {:op "session-status" :sessionId id})))))
      (finally (editor/close! views)))))

(deftest committed-extension-removal-keeps-later-extensions-and-run-identity
  (let [views (atom {}) cmd #(server/command! views %)]
    (try
      (let [opened (cmd {:op "run" :pane "a" :source "(define base 1)"
                         :extensions ["(define capability 10)" "(define later (+ capability 2))" "(define independent 30)"]})
            id (:sessionId opened) owner (boundary views id)
            [first-id second-id third-id] (mapv :id (:appliedExtensions opened))
            before (host/current-session owner)]
        (is (= 12 (compiler/binding-value before 'later)))
        (doseq [request [{:op "remove-extension" :pane "a" :generation "stale" :extensionId first-id}
                         {:op "remove-extension" :pane "a" :generation (:generation opened) :extensionId "base"}]]
          (is (thrown? Exception (cmd request)))
          (is (= (compiler/network before) (compiler/network (host/current-session owner)))))
        (let [removed (cmd {:op "remove-extension" :pane "a" :generation (:generation opened) :extensionId first-id})
              current (host/current-session owner)]
          (is (= id (:sessionId removed)))
          (is (not= (:runId opened) (:runId removed)))
          (is (= [second-id third-id] (mapv :id (:appliedExtensions removed))))
          (is (= (subvec (:extensions opened) 1) (:extensions removed)))
          (is (= 30 (compiler/binding-value current 'independent)))
          (is (value/nothing? (data/payload (compiler/binding-value current 'later))))
          (is (= "active" (:lifecycle removed)))
          (is (identical? owner (boundary views id)))
          (is (thrown? Exception (cmd {:op "remove-extension" :sessionId id :generation (:generation opened) :extensionId second-id}))))
        (let [status (cmd {:op "session-status" :sessionId id})
              current (host/current-session owner)]
          (is (thrown? Exception (cmd {:op "run" :pane "a" :generation (:generation status) :source "("})))
          (is (= (compiler/network current) (compiler/network (host/current-session owner))))
          (is (= status (cmd {:op "session-status" :sessionId id})))))
      (finally (editor/close! views)))))

(defn callback-extension [capability]
  (compiler/session-extension
   {:id :controlled-effect
    :primitives {'fetch (operators/async-op (fn [make] (make :fetch)))}
    :dependencies {:fetch capability}
    :files {"effect.lain" "(define result (fetch 21))"}}))

(deftest hot-extension-delivery-replies-and-verified-replacement
  (let [starts (atom 0) stops (atom 0) emit (promise)
        capability (dependency/callback-dependency
                    (fn [_ reply!]
                      (swap! starts inc) (deliver emit reply!)
                      (fn [] (swap! stops inc) {:status :closed})))
        initial (compiler/create-session)
        candidate (compiler/extend-session initial (callback-extension capability))
        owner (host/open! initial {:auto-advance? false})]
    (try
      (is (zero? @starts) "compilation starts no dependency")
      (host/extend! owner (callback-extension capability))
      (is (not= ::timeout (deref emit 5000 ::timeout)))
      (await! #(every? :start-finished? (vals (host/receipts owner))))
      (is (= 1 @starts))
      (is (= (:id (compiler/session-identity initial)) (:id (compiler/session-identity (host/current-session owner)))))
      (is (value/nothing? (data/payload (compiler/binding-value (host/current-session owner) 'result))))
      (is (= :accepted (@emit 42)))
      (is (= 1 (host/pending-count owner)))
      (host/step! owner)
      (is (= 42 (data/payload (compiler/binding-value (host/current-session owner) 'result))))
      (let [before (host/current-session owner)]
        (is (thrown? Exception (host/replace! owner (compiler/create-session))))
        (is (= (compiler/network before) (compiler/network (host/current-session owner))))
        (is (zero? @stops)))
      (let [fresh (compiler/create-session (assoc (compiler/session-identity candidate) :run-id (random-uuid)))]
        (host/replace! owner fresh)
        (is (= 1 @stops))
        (is (= 1 @starts))
        (is (= :closed (@emit 99)))
        (is (zero? (host/pending-count owner)))
        (is (= :active (host/lifecycle owner))))
      (finally (host/close! owner)))
    (is (= 1 @stops))))

(deftest cleanup-failure-blocks-activation-and-retry
  (let [starts (atom 0) stops (atom 0)
        capability (dependency/callback-dependency
                    (fn [_ _]
                      (swap! starts inc)
                      (fn [] (swap! stops inc) {:status :unverified})))
        initial (compiler/extend-session (compiler/create-session) (callback-extension capability))
        owner (host/open! initial {:auto-advance? false})
        fresh (compiler/create-session (assoc (compiler/session-identity initial) :run-id (random-uuid)))]
    (host/step! owner)
    (await! #(and (= 1 @starts) (every? :start-finished? (vals (host/receipts owner)))))
    (is (thrown? Exception (host/replace! owner fresh)))
    (is (= :retirement-failed (host/lifecycle owner)))
    (is (= (:run-id (compiler/session-identity initial)) (:run-id (compiler/session-identity (host/current-session owner)))))
    (is (= 1 @stops))
    (is (thrown? Exception (host/replace! owner fresh)))
    (is (thrown? Exception (host/step! owner)))
    (is (= 1 @starts))
    (is (= 1 @stops))
    (is (thrown? Exception (host/close! owner)))
    (is (= :retirement-failed (host/lifecycle owner)))
    (is (= 1 @stops))))

(deftest managed-trace-snapshots-survive-extension-update-and-retraction
  (let [views (atom {}) cmd #(server/command! views %)
        source (str "(load-lain \"propagators/compiler_2/runtime/lain/tracers.lain\") "
                    "(define step (network [x] (+ x 1))) (define input) (define output (step input)) "
                    "(define trace-result (trace:high-level (trace:semantic input) \"input\" \"output\"))")]
    (try
      (let [opened (cmd {:op "open-session" :source source})
            owner (boundary views (:sessionId opened))
            snapshots (atom [])]
        (doseq [[revision update] [[1 (publication/active :trace 1 3)] [2 (publication/active :trace 2 5)]
                                   [3 (publication/retracted :trace 3)] [4 (publication/active :trace 4 7)]]]
          (host/submit! owner (compiler/cell-input (compiler/binding-cell (host/current-session owner) 'input) update))
          (host/step! owner)
          (let [current (host/current-session owner)
                captured (data/payload (compiler/binding-value current 'trace-result))
                graph (high-level/display-graph captured)]
            (is (= '#{input output} (set (vals (:nodes graph)))))
            (is (seq (:edges graph)))
            (swap! snapshots conj [captured (capture/root-graph captured)]))
          (when (= revision 1)
            (cmd {:op "extend" :sessionId (:sessionId opened) :generation (:generation opened) :source "(define extra 9)"})))
        (doseq [[captured snapshot] @snapshots] (is (= snapshot (capture/root-graph captured))))
        (is (= 8 (data/payload (compiler/binding-value (host/current-session owner) 'output)))))
      (finally (editor/close! views)))))

(deftest explicit-legacy-switch-retires-the-managed-owner
  (let [views (atom {}) cmd #(server/command! views %)]
    (try
      (let [opened (cmd {:op "run" :pane "a" :source "(define result 42)"})
            owner (boundary views (:sessionId opened))]
        (cmd {:op "reopen-session" :sessionId (:sessionId opened) :pane "second-view"})
        (let [legacy (cmd {:op "run" :pane "a" :compilerSession false
                           :generation (:generation opened) :source "(define result 7)"})]
          (is (= :closed (host/lifecycle owner)))
          (is (false? (:compilerSession legacy)))
          (is (= "legacy" (:runtime legacy)))
          (is (nil? (get @views "second-view")))
          (is (empty? (:sessions (cmd {:op "list-sessions"}))))
          (is (= "7" (:value (cmd {:op "inspect" :sessionId (:sessionId legacy)
                                   :generation (:generation legacy) :display fixture/display}))))
          (let [extended (cmd {:op "extend" :pane "a" :generation (:generation legacy) :source "(define extra 8)"})]
            (is (= (:generation extended) (:generation (cmd {:op "session-status" :sessionId (:sessionId legacy)})))))
          (is (thrown? Exception (cmd {:op "session-status" :sessionId (:sessionId opened)})))))
      (finally (editor/close! views)))))

(deftest native-clocks-defer-declared-dependencies-to-the-managed-host
  (let [views (atom {}) starts (atom 0) stops (atom 0) emit (promise)
        opened (editor/open-session! views {:source "(load-primitive-environment \"extensions/runtime_clock.clj\" :extensions.runtime-clock/primitive-bindings) (define ticks (clock-in 20))"})
        owner (boundary views (:sessionId opened)) session (host/runtime-session owner)
        capability (dependency/callback-dependency (fn [_ reply!] (swap! starts inc) (deliver emit reply!) (fn [] (swap! stops inc) {:status :closed})))]
    (try
      (host/extend! owner (callback-extension capability))
      (is (not= ::timeout (deref emit 5000 ::timeout)))
      (let [epoch (apply max 0 (vals (:clock/epochs @session)))]
        (await! #(> (apply max 0 (vals (:clock/epochs @session))) (+ epoch 2))))
      (is (= 1 @starts))
      (is (value/nothing? (data/payload (compiler/binding-value (host/current-session owner) 'result))))
      (is (= :accepted (@emit 42)))
      ;; An admitted callback can leave the queue before its result is committed.
      ;; Wait for the observable result rather than treating queue emptiness as
      ;; a completion barrier while the native clock also advances the owner.
      (await! #(= 42 (data/payload (compiler/binding-value (host/current-session owner) 'result))))
      (is (= 42 (data/payload (compiler/binding-value (host/current-session owner) 'result))))
      (finally (editor/close! views)))
    (is (= 1 @stops))
    (let [epochs (:clock/epochs @session)]
      (Thread/sleep 60)
      (is (= epochs (:clock/epochs @session)) "Retired clock callbacks cannot mutate the closed owner"))))

(deftest removal-retains-a-later-extensions-own-committed-imports
  (let [views (atom {}) cmd #(server/command! views %)
        imports "(load-primitive-environment \"extensions/runtime_clock.clj\" :extensions.runtime-clock/primitive-bindings) "]
    (try
      (let [opened (cmd {:op "open-session" :source "(define base 1)"
                         :extensions [(str imports "(define first-capability 10)")
                                      (str imports "(define retained-capability 20)")]})
            [first-id retained-id] (mapv :id (:appliedExtensions opened))
            removed (cmd {:op "remove-extension" :sessionId (:sessionId opened)
                          :generation (:generation opened) :extensionId first-id})
            current (host/current-session (boundary views (:sessionId opened)))]
        (is (= [retained-id] (mapv :id (:appliedExtensions removed))))
        (is (= 20 (compiler/binding-value current 'retained-capability)))
        (is (not (value/nothing? (compiler/binding-value current 'clock-in))))
        (is (= (:sessionId opened) (:sessionId removed)))
        (is (not= (:runId opened) (:runId removed))))
      (finally (editor/close! views)))))
