(ns propagators.compiler-2.immutable-session-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.session :as session]
            [propagators.compiler-2.session.dependencies :as dependency]
            [propagators.compiler-2.session.host :as host]
            [propagators.compiler-2.session.files :as files]
            [propagators.compiler-2.session.operators :as operators]
            [propagators.stdlib.source-publication :as publication]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]))

(defn loaded [source]
  (session/extend-session (session/create-session)
    (files/prepare-extension! {:id :program
      :files {"main.lain" (str "(load-lain \"propagators/compiler_2/runtime/lain/combinator.lain\")\n" source)}})))

(defn observed [compiler sym]
  (data/payload (session/binding-value compiler sym)))

(defn publish [compiler sym revision candidate]
  (:session (session/advance compiler
               [(session/cell-input (session/binding-cell compiler sym)
                  (publication/active sym revision candidate))])))

(deftest real-compiler-session-is-immutable
  (let [empty (session/create-session)
        compiled (session/extend-session empty
                   (session/session-extension {:id :program
                     :files {"main" "(define input) (define result (+ input 1))"}}))
        updated (publish compiled 'input 1 10)]
    (is (satisfies? session/CompilerSession compiled))
    (is (satisfies? session/OpenSession compiled))
    (is (satisfies? session/SessionDependencies compiled))
    (is (value/nothing? (observed compiled 'result)))
    (is (= 11 (observed updated 'result)))
    (is (empty? (session/source-files empty)))
    (is (not (identical? compiled updated)))))

(deftest late-primitive-cannot-replace-a-caller-owned-source-alias
  (let [compiler (loaded "(define source) (define alias source)
                          (define result (+ source 1))")
        source-id (session/binding-cell compiler 'source)
        extension (session/session-extension
                    {:id :conflicting-alias
                     :primitives {'alias (operators/prim-op (fn [make] (make inc)))}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Conflicting binding"
          (session/extend-session compiler extension)))
    (is (= source-id (session/binding-cell compiler 'source)))
    (let [updated (publish compiler 'source 1 10)]
      (is (= 10 (observed updated 'alias)))
      (is (= 11 (observed updated 'result))))))

(deftest canonical-gur-follows-callables-through-live-compound-owners
  (let [compiler (loaded
                   "(define flag) (define input) (define left-offset)
                    (define right-offset) (define independent)
                    (define relay (network [x] x))
                    (define left (struct :inner
                      (struct :call (network [x] (+ x left-offset)) :unknown independent)))
                    (define right (struct :inner
                      (struct :call (network [x] (+ x right-offset)) :unknown independent)))
                    (define selected (relay (relay (if flag left right))))
                    (define invoke ((slot :call) ((slot :inner) selected)))
                    (define result (invoke input))")
        output-id (session/binding-cell compiler 'result)]
    (is (value/nothing? (observed compiler 'result)))
    (loop [compiler compiler
           changes [['input 1 10 value/nothing]
                    ['left-offset 1 1 value/nothing]
                    ['right-offset 1 10 value/nothing]
                    ['flag 1 true 11] ['flag 2 false 20]
                    ['left-offset 2 100 20] ['input 0 999 20]
                    ['flag 3 value/nothing value/nothing]
                    ['input 2 20 value/nothing] ['flag 4 true 120]
                    ['left-offset 3 value/contradiction value/nothing]
                    ['left-offset 4 1 21]]]
      (when-let [[sym revision candidate expected] (first changes)]
        (let [updated (publish compiler sym revision candidate)]
          (is (= expected (observed updated 'result)) (pr-str [sym revision]))
          (when (value/contradiction? candidate)
            (is (value/contradiction? (observed updated sym)))
            (is (not= value/nothing
                      (net/network-cell-content (session/network updated) output-id))
                "Blocked GUR preserves support/state for the contradictory captured input"))
          (is (= output-id (session/binding-cell updated 'result)))
          (recur updated (next changes)))))))

(deftest atomic-idempotent-monotone-catalogs
  (let [primitive (operators/prim-op (fn [make] (make inc)))
        capability (dependency/callback-dependency (fn [_ _] nil))
        extension (session/session-extension
                    {:id :all :primitives {'increment primitive}
                     :dependencies {:service capability}
                     :files {"main" "(define result (increment 10))"}})
        empty (session/create-session)
        once (session/extend-session empty extension)
        twice (session/extend-session once extension)
        alias-extension (session/session-extension
                          (assoc (session/extension-parts extension) :id :same-capabilities))
        aliased (session/extend-session once alias-extension)]
    (is (identical? (session/network once) (session/network aliased)))
    (is (identical? aliased (session/extend-session aliased alias-extension)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"identity already declared"
          (session/extend-session aliased
            (session/session-extension {:id :same-capabilities
              :files {"different" "(define different 5)"}}))))
    (is (= 11 (observed once 'result)))
    (is (identical? once twice))
    (is (= (net/net-graph (session/network once))
           (net/net-graph (session/network twice))))
    (is (= {:service capability} (session/dependency-declarations once)))
    (is (= {} (session/dependency-declarations empty)))
    (is (thrown? clojure.lang.ExceptionInfo
          (session/extend-session once
            (session/session-extension {:id :conflict :primitives {'increment 99}
              :dependencies {:another capability} :files {"other" "(define extra 5)"}}))))
    (is (= {:service capability} (session/dependency-declarations once)))
    (is (thrown? clojure.lang.ExceptionInfo
          (session/extend-session once
            (session/session-extension {:id :bad :primitives {'new-primitive primitive}
              :dependencies {:new-service capability}
              :files {"bad" "(define)"}}))))
    (is (= #{"main"} (set (keys (session/source-files once)))))))

(deftest primitive-maker-declares-without-running-implementation
  (let [calls (atom [])
        declarations (atom [])
        f (fn [& args] (swap! calls conj args))
        operator (operators/prim-op (fn [make] (make f)))
        callable (operators/as-propagator-operator operator 'sample
                   {:declare-primitive (fn [implementation cells]
                                         (swap! declarations conj [implementation cells])
                                         :output-cell)
                    :bind (fn [_ callable] callable)})]
    (is (empty? @calls))
    (is (= :output-cell (callable [:input])))
    (is (= [[f [:input]]] @declarations))
    (is (empty? @calls))))

(deftest canonical-gur-returned-callables-and-compound-chains
  (doseq [[source answer]
          [["(define apply-twice (network [f x] (f (f x))))
             (define inc (network [x] (+ x 1)))
             (define result (apply-twice inc 10))" 12]
           ["(define outer (struct :inner (struct :call (network [x] (+ x 1)))))
             (define invoke ((slot :call) ((slot :inner) outer)))
             (define result (invoke 10))" 11]]]
    (is (= answer (observed (loaded source) 'result)))))

(deftest chained-compounds-follow-live-selected-owner
  (let [source "(define flag) (define left-value) (define right-value) (define unrelated)
                (define relay (network [x] x))
                (define left (struct :inner (struct :value left-value :unknown unrelated)))
                (define right (struct :inner (struct :value right-value :unknown unrelated)))
                (define selected (relay (relay (if flag left right))))
                (define result ((slot :value) ((slot :inner) selected)))
                (define missing ((slot :missing) selected))"
        initial (loaded source)]
    (is (value/nothing? (observed initial 'result)))
    (loop [compiler initial
           changes [['flag 1 true value/nothing] ['left-value 1 10 10]
                    ['right-value 1 20 10] ['left-value 2 11 11]
                    ['flag 2 false 20] ['left-value 3 12 20]
                    ['right-value 2 21 21] ['flag 3 value/nothing value/nothing]
                    ['flag 4 true 12]]]
      (when-let [[sym revision candidate expected] (first changes)]
        (let [next-session (publish compiler sym revision candidate)]
          (is (= expected (observed next-session 'result)) (pr-str [sym revision]))
          (is (value/nothing? (observed next-session 'missing)))
          (recur next-session (next changes)))))))

(deftest late-extension-resolves-canonical-lookup-and-new-forms
  (let [pending (loaded "(define input) (define result (late input))")
        extension (session/session-extension {:id :late
                    :primitives {'late (operators/prim-op (fn [make] (make inc)))}})
        extended (session/extend-session pending extension)
        ready (publish extended 'input 1 10)
        appended (session/extend-session ready
                   (session/session-extension {:id :more
                     :files {"more" "(define another (late 20))"}}))]
    (is (value/nothing? (observed pending 'result)))
    (is (= 11 (observed ready 'result)))
    (is (= 21 (observed appended 'another)))
    (is (identical? appended (session/extend-session appended extension)))))

(declare callback-program await!)

(deftest input-history-is-explicitly-opt-in
  (let [source "(define input) (define result (+ input 1))"
        ordinary (publish (loaded source) 'input 1 10)
        recorded (session/extend-session
                   (session/create-session {:id :history :run-id :history-run
                                            :runtime-options {:record-inputs? true}})
                   (session/session-extension {:id :program :files {"main" source}}))
        updated (publish recorded 'input 1 10)]
    (is (empty? (:runtime/inputs (session/compiler-state ordinary))))
    (is (= 11 (observed ordinary 'result)))
    (is (= 1 (count (:runtime/inputs (session/compiler-state updated)))))
    (is (= 11 (observed updated 'result)))))

(deftest uncertain-startup-is-not-retried-without-reconciliation
  (let [calls (atom 0)
        compiler (callback-program
                   (dependency/callback-dependency
                     (fn [_ _]
                       (swap! calls inc)
                       (throw (ex-info "Transport disconnected during launch" {})))))
        boundary (host/open! compiler {:auto-advance? false})]
    (try
      (host/step! boundary)
      (await! #(some (fn [receipt] (= :uncertain (:status receipt)))
                     (vals (host/receipts boundary))))
      (let [id (first (keys (host/receipts boundary)))]
        (dotimes [_ 3] (host/step! boundary))
        (is (= 1 @calls))
        (is (= 1 (count (host/failures boundary))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"verify cleanup"
              (host/reconcile! boundary id (fn [_] {:status :unknown}))))
        (is (= :uncertain (get-in (host/receipts boundary) [id :status])))
        (is (= {:status :closed}
               (host/reconcile! boundary id
                 (fn [request]
                   (is (= id (:boundary/id request)))
                   {:status :closed}))))
        (is (= :reconciled (get-in (host/receipts boundary) [id :status])))
        (host/step! boundary)
        (is (= 1 @calls)))
      (finally (host/close! boundary)))))

(deftest rerun-uses-definitions-with-a-fresh-run-identity
  (let [compiler (loaded "(define input) (define result (+ input 1))")
        updated (publish compiler 'input 1 10)
        fresh (session/rerun updated :fresh-run)]
    (is (= 11 (observed updated 'result)))
    (is (value/nothing? (observed fresh 'result)))
    (is (= (session/source-files updated) (session/source-files fresh)))
    (is (= :fresh-run (:run-id (session/session-identity fresh))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (session/rerun updated (:run-id (session/session-identity updated)))))))

(defn await! [predicate]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (if (predicate)
        true
        (if (> (System/nanoTime) deadline)
          (throw (ex-info "Timed out waiting for dependency delivery" {}))
          (do (Thread/sleep 5) (recur)))))))

(defn callback-program [capability]
  (session/extend-session (session/create-session)
    (session/session-extension {:id :callback
      :primitives {'fetch (operators/async-op (fn [make] (make :fetch)))}
      :dependencies {:fetch capability}
      :files {"main" "(define result (fetch 21))"}})))

(deftest immediate-replies-are-later-round-inputs
  (let [calls (atom 0)
        compiler (callback-program
                   (dependency/callback-dependency
                     (fn [context emit!]
                       (swap! calls inc)
                       (emit! (* 2 (first (:arguments context))))
                       ((:complete! context))
                       nil)))
        boundary (host/open! compiler {:auto-advance? false})]
    (try
      (is (zero? @calls))
      (host/step! boundary)
      (await! #(= 1 (host/pending-count boundary)))
      (is (= 1 @calls))
      (is (= 1 (host/pending-count boundary)))
      (is (value/nothing? (observed (host/current-session boundary) 'result)))
      (host/step! boundary)
      (is (= 42 (observed (host/current-session boundary) 'result)))
      (host/step! boundary)
      (is (= 1 @calls))
      (finally (host/close! boundary)))))

(deftest flexible-streaming-output-normalizes-before-next-round
  (let [emit (promise)
        stops (atom 0)
        capability (dependency/callback-dependency
                     (fn [_ emit!]
                       (deliver emit emit!)
                       (fn [] (swap! stops inc) {:status :closed}))
                     (fn [{:keys [revision value]}]
                       (publication/active :stream revision value)))
        boundary (host/open! (callback-program capability) {:auto-advance? false})]
    (try
      (host/step! boundary)
      (is (not= ::timeout (deref emit 5000 ::timeout)))
      (await! #(every? :start-finished? (vals (host/receipts boundary))))
      (doseq [[revision value] [[1 10] [2 11] [3 12]]]
        (is (= :accepted (@emit {:revision revision :value value})))
        (host/step! boundary)
        (is (= value (observed (host/current-session boundary) 'result))))
      (let [extra (session/session-extension {:id :unrelated :files {"extra" "(define extra 5)"}})]
        (host/extend! boundary extra)
        (is (= :accepted (@emit {:revision 4 :value 13})))
        (host/step! boundary)
        (is (= 13 (observed (host/current-session boundary) 'result))))
      (let [id (first (keys (host/receipts boundary)))]
        (host/cancel! boundary id)
        (is (= :closed (@emit {:revision 5 :value 14})))
        (is (= 1 @stops)))
      (finally (host/close! boundary)))
    (is (= 1 @stops))))

(deftest external-subscription-backpressure-and-normalized-messages
  (let [emit (promise)
        starts (atom 0)
        compiler (session/extend-session (loaded "(define input) (define result (+ input 1))")
                   (session/session-extension {:id :events
                     :dependencies {:events (dependency/callback-dependency
                                              (fn [_ emit!]
                                                (swap! starts inc)
                                                (deliver emit emit!)
                                                nil))}}))
        boundary (host/open! compiler {:auto-advance? false :capacity 1})
        id (session/binding-cell compiler 'input)]
    (try
      (host/subscribe! boundary :events :events)
      (host/subscribe! boundary :events :events)
      (is (not= ::timeout (deref emit 5000 ::timeout)))
      (is (= 1 @starts))
      (is (= :accepted (@emit (session/cell-input id 10))))
      (is (= :backpressured (@emit (session/cell-input id 20))))
      (host/step! boundary)
      (is (= 11 (observed (host/current-session boundary) 'result)))
      (is (thrown? clojure.lang.ExceptionInfo (@emit {:ambiguous 42})))
      (is (zero? (host/pending-count boundary)))
      (finally (host/close! boundary)))
    (is (= :closed (@emit (session/cell-input id 99))))))

(deftest output-can-commit-while-the-handler-is-still-running
  (let [release (promise)
        compiler (callback-program
                   (dependency/callback-dependency
                     (fn [_ emit!]
                       (emit! (publication/active :long-work 1 10))
                       (when (= ::timeout (deref release 5000 ::timeout))
                         (throw (ex-info "Handler release timed out" {})))
                       (emit! (publication/active :long-work 2 20))
                       nil)))
        boundary (host/open! compiler)]
    (try
      (let [producing-turn (host/step! boundary)]
        (is (value/nothing? (observed producing-turn 'result))))
      (await! #(= 10 (observed (host/current-session boundary) 'result)))
      (is (not (realized? release)))
      (is (some #(not (:start-finished? %)) (vals (host/receipts boundary))))
      (deliver release true)
      (await! #(= 20 (observed (host/current-session boundary) 'result)))
      (is (= 20 (observed (host/current-session boundary) 'result)))
      (finally (deliver release true) (host/close! boundary)))))

(deftest changed-input-fences-old-replies-and-withdraws-the-old-result
  (let [emitters (atom {})
        capability (dependency/callback-dependency
                     (fn [context emit!]
                       (swap! emitters assoc (first (:arguments context)) emit!)
                       nil))
        compiler (session/extend-session (session/create-session)
                   (session/session-extension {:id :changing
                     :primitives {'fetch (operators/async-op (fn [make] (make :fetch)))}
                     :dependencies {:fetch capability}
                     :files {"main" "(define input) (define result (fetch input))"}}))
        boundary (host/open! compiler {:auto-advance? false})
        input-id (session/binding-cell compiler 'input)]
    (try
      (host/submit! boundary (session/cell-input input-id (publication/active :input 1 1)))
      (host/step! boundary)
      (await! #(contains? @emitters 1))
      (is (= :accepted ((get @emitters 1) 10)))
      (host/step! boundary)
      (is (= 10 (observed (host/current-session boundary) 'result)))
      (host/submit! boundary (session/cell-input input-id (publication/active :input 2 2)))
      (host/step! boundary)
      (await! #(contains? @emitters 2))
      (is (value/nothing? (observed (host/current-session boundary) 'result)))
      (is (= :closed ((get @emitters 1) 999)))
      (is (= :accepted ((get @emitters 2) 20)))
      (host/step! boundary)
      (is (= 20 (observed (host/current-session boundary) 'result)))
      (finally (host/close! boundary)))))

(deftest explicit-completion-allows-zero-output-and-cleans-up-once
  (let [emitter (promise)
        stops (atom 0)
        compiler (callback-program
                   (dependency/callback-dependency
                     (fn [context emit!]
                       (deliver emitter emit!)
                       ((:complete! context))
                       (fn [] (swap! stops inc) {:status :closed}))))
        boundary (host/open! compiler {:auto-advance? false})]
    (try
      (host/step! boundary)
      (await! #(some (fn [r] (= :closed (:cleanup r))) (vals (host/receipts boundary))))
      (is (= 1 @stops))
      (is (= :closed (@emitter 42)))
      (is (zero? (host/pending-count boundary)))
      (is (value/nothing? (observed (host/current-session boundary) 'result)))
      (finally (host/close! boundary)))
    (is (= 1 @stops))))

(deftest cleanup-failure-is-sticky-and-never-reported-as-closed
  (let [stops (atom 0)
        compiler (callback-program
                   (dependency/callback-dependency
                     (fn [_ _] (fn [] (swap! stops inc) {:status :failed}))))
        boundary (host/open! compiler {:auto-advance? false})]
    (host/step! boundary)
    (await! #(every? :start-finished? (vals (host/receipts boundary))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cleanup"
          (host/close! boundary)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cleanup"
          (host/close! boundary)))
    (is (= 1 @stops))
    (is (= :failed (:cleanup (first (vals (host/receipts boundary))))))))

(deftest rerun-fences-old-run-callbacks
  (let [emitters (atom [])
        compiler (callback-program
                   (dependency/callback-dependency
                     (fn [_ emit!] (swap! emitters conj emit!) nil)))
        boundary (host/open! compiler {:auto-advance? false})]
    (try
      (host/step! boundary)
      (await! #(and (= 1 (count @emitters))
                    (every? :start-finished? (vals (host/receipts boundary)))))
      (let [old-emitter (first @emitters)]
        (host/rerun! boundary :fresh-run)
        (await! #(= 2 (count @emitters)))
        (is (= :closed (old-emitter 999)))
        (is (= :accepted ((second @emitters) 42)))
        (host/step! boundary)
        (is (= 42 (observed (host/current-session boundary) 'result))))
      (finally (host/close! boundary)))))

(deftest invalid-async-dependency-is-an-atomic-declaration-failure
  (let [compiler (session/create-session)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"undeclared dependency"
          (session/extend-session compiler
            (session/session-extension {:id :invalid
              :primitives {'fetch (operators/async-op (fn [make] (make :missing)))}
              :files {"bad" "(define result (fetch 1))"}}))))
    (is (empty? (session/source-files compiler)))
    (is (empty? (session/dependency-declarations compiler)))))

(deftest exhausted-propagation-budget-does-not-publish-a-candidate
  (let [compiler (session/create-session
                   {:id :budget :run-id :budget-run
                    :propagation-options {:max-steps 1 :timeout-ms 10000}})]
    (is (thrown? clojure.lang.ExceptionInfo
          (session/extend-session compiler
            (session/session-extension {:id :too-large
              :files {"main" "(define result (+ 20 22))"}}))))
    (is (empty? (session/source-files compiler)))))
