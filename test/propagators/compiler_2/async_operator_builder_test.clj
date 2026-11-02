(ns propagators.compiler-2.async-operator-builder-test
  "Production acceptance for the async operator builder."
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.session :as session]
            [propagators.compiler-2.session.operators :as operators]
            [propagators.compiler-2.session.dependencies :as dependency]
            [propagators.compiler-2.session.files :as files]
            [propagators.compiler-2.session.host :as host]
            [propagators.compiler-2.immutable-session-test :refer [await! observed publish]]
            [propagators.stdlib.source-publication :as publication]))

(defn- extension [id bindings source]
  (session/session-extension {:id id :primitives bindings :files {(name id) source}}))

(deftest injected-builder-preserves-current-round-cell-output
  (let [routed (atom 0)
        constructed (atom 0)
        installations (atom [])
        boundary (host/open! (session/create-session) {:auto-advance? false})]
    (try
      (let [interpreter (host/effect-interpreter boundary (fn [_] (swap! routed inc)))
            operation (operators/async-op interpreter
                        (fn [make]
                          (swap! constructed inc)
                          (let [call (make #(operators/cell-output (inc %)))]
                            (is (fn? call))
                            (fn [cells]
                              (let [output (call cells)]
                                (swap! installations conj [cells output]) output)))))
            declaration (extension :direct {'operation operation}
                                   "(define input) (define result (operation input))")
            compiler (session/extend-session (host/current-session boundary) declaration)
            output (session/binding-cell compiler 'result)
            ready (publish compiler 'input 1 10)]
        (is (= 11 (observed ready 'result)))
        (is (= output (session/binding-cell ready 'result)))
        (is (= 1 (count (set (map second @installations)))))
        (is (= 1 @constructed))
        (is (zero? @routed))
        (is (zero? (host/pending-count boundary)))
        (is (empty? (host/receipts boundary)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"effect interpreter"
                             (operators/async-primitive-maker (fn [_ _] nil)))))
      (finally (host/close! boundary)))))

(deftest effect-receiver-routes-only-after-publication
  (let [routed (atom [])
        boundary (host/open! (session/create-session) {:auto-advance? false})]
    (try
      (let [interpreter (host/effect-interpreter boundary
                          (fn [input]
                            (swap! routed conj
                                   [input (value/nothing? (observed (host/current-session boundary) 'result))])
                            (operators/next-round-input (inc input))))
            declaration (extension :later
                          {'later (operators/async-op interpreter
                                    (fn [make] (make operators/effect-output)))}
                          "(define result (later 10))")
            candidate (session/extend-session (host/current-session boundary) declaration)]
        (is (value/nothing? (observed candidate 'result)))
        (is (empty? @routed) "Pure compilation does not invoke the interpreter")
        (host/extend! boundary declaration)
        (await! #(= 1 (host/pending-count boundary)))
        (is (= [[10 true]] @routed))
        (is (value/nothing? (observed (host/current-session boundary) 'result)))
        (host/step! boundary)
        (is (= 11 (observed (host/current-session boundary) 'result)))
        (dotimes [_ 3] (host/step! boundary))
        (is (= 1 (count @routed)))
        (is (= 1 (count (host/receipts boundary)))))
      (finally (host/close! boundary)))))

(deftest injected-dependency-normalizes-flexible-output
  (let [emitter (promise)
        context-seen (promise)
        starts (atom 0)
        stops (atom 0)
        service (dependency/callback-dependency
                 (fn [context emit!]
                   (swap! starts inc)
                   (deliver context-seen context)
                   (deliver emitter emit!)
                   (emit! [1 10])
                   (fn [] (swap! stops inc) {:status :closed}))
                 (fn [[revision result]] (publication/active :stream revision result)))
        compiler (session/extend-session (session/create-session)
                   (session/session-extension {:id :service :dependencies {:service service}}))
        boundary (host/open! compiler {:auto-advance? false})]
    (try
      (let [interpreter (host/effect-interpreter boundary
                          #(operators/dependency-input :service {:number %}))
            declaration (extension :stream
                          {'stream (operators/async-op interpreter
                                     (fn [make] (make operators/effect-output)))}
                          "(define result (stream 21))")
            candidate (session/extend-session compiler declaration)]
        (is (value/nothing? (observed candidate 'result)))
        (is (zero? @starts))
        (host/extend! boundary declaration)
        (await! #(= 1 (host/pending-count boundary)))
        (is (= [21] (:arguments @context-seen)))
        (is (= {:number 21} (:input @context-seen)))
        (is (not-any? #(contains? @context-seen %) [:dependencies :interpreter :capability]))
        (is (value/nothing? (observed (host/current-session boundary) 'result)))
        (host/step! boundary)
        (is (= 10 (observed (host/current-session boundary) 'result)))
        (is (= :accepted (@emitter [2 11])))
        (is (= 10 (observed (host/current-session boundary) 'result)))
        (host/step! boundary)
        (is (= 11 (observed (host/current-session boundary) 'result)))
        (host/extend! boundary (extension :unrelated {} "(define extra 5)"))
        (is (= :accepted (@emitter [3 12])))
        (host/step! boundary)
        (is (= 12 (observed (host/current-session boundary) 'result)))
        ((:complete! @context-seen))
        (await! #(= 1 @stops))
        (is (= :closed (@emitter [4 99])))
        (is (= 1 @starts)))
      (finally (host/close! boundary)))
    (is (= 1 @stops))))

(deftest frozen-module-preserves-injected-builder
  (let [boundary (host/open! (session/create-session) {:auto-advance? false})]
    (try
      (let [interpreter (host/effect-interpreter boundary operators/next-round-input)]
        (host/extend! boundary
          (extension :module
            {'increment (operators/frozen-primitive [:module 1 'increment]
                          (operators/prim-op (fn [make] (make inc))))
             'later (operators/frozen-primitive [:module 1 'later]
                      (operators/async-op interpreter
                        (fn [make] (make operators/effect-output))))}
            "(define result (later (increment 10)))"))
        (await! #(pos? (host/pending-count boundary)))
        (is (value/nothing? (observed (host/current-session boundary) 'result)))
        (host/step! boundary)
        (is (= 11 (observed (host/current-session boundary) 'result))))
      (finally (host/close! boundary)))))

(deftest interpreter-rejects-another-boundarys-ingress
  (let [owner (host/open! (session/create-session) {:auto-advance? false})
        other (host/open! (session/create-session) {:auto-advance? false})]
    (try
      (let [interpreter (host/effect-interpreter owner operators/next-round-input)]
        (host/extend! other
          (extension :foreign
            {'later (operators/async-op interpreter (fn [make] (make operators/effect-output)))}
            "(define result (later 10))"))
        (await! #(seq (host/failures other)))
        (is (re-find #"another session boundary" (.getMessage (first (host/failures other)))))
        (is (zero? (host/pending-count owner)))
        (is (zero? (host/pending-count other)))
        (doseq [id (keys (host/receipts other))]
          (host/reconcile! other id (fn [_] {:status :closed}))))
      (finally (host/close! other) (host/close! owner)))))

(deftest interpreter-uses-existing-admission-capacity
  (let [entered (promise)
        release (promise)
        boundary (host/open! (session/create-session) {:auto-advance? false :capacity 1})]
    (try
      (let [interpreter (host/effect-interpreter boundary
                          (fn [input] (deliver entered true) @release
                            (operators/next-round-input input)))]
        (host/extend! boundary
          (extension :bounded
            {'later (operators/async-op interpreter (fn [make] (make operators/effect-output)))}
            "(define inlet) (define result (later 10))"))
        (is (= true (deref entered 5000 :timeout)))
        (is (= :accepted
               (host/submit! boundary
                 (session/cell-input (session/binding-cell (host/current-session boundary) 'inlet) 7))))
        (deliver release true)
        (await! #(seq (host/failures boundary)))
        (is (= :backpressured (:status (ex-data (first (host/failures boundary))))))
        (is (= 1 (host/pending-count boundary)))
        (is (value/nothing? (observed (host/current-session boundary) 'result)))
        (doseq [id (keys (host/receipts boundary))]
          (host/reconcile! boundary id (fn [_] {:status :closed}))))
      (finally (deliver release true) (host/close! boundary)))))

(deftest injected-interpreter-preserves-rerun-fences-and-cleanup
  (let [handles (atom [])
        stops (atom 0)
        service (dependency/callback-dependency
                 (fn [context emit!]
                   (swap! handles conj {:context context :emit! emit!})
                   (fn [] (swap! stops inc) {:status :closed}))
                 (fn [[revision result]] (publication/active :rerun-stream revision result)))
        compiler (session/extend-session (session/create-session)
                   (session/session-extension {:id :service :dependencies {:service service}}))
        boundary (host/open! compiler {:auto-advance? false})]
    (try
      (let [interpreter (host/effect-interpreter boundary
                          #(operators/dependency-input :service %))]
        (host/extend! boundary
          (extension :rerunnable
            {'stream (operators/async-op interpreter (fn [make] (make operators/effect-output)))}
            "(define result (stream 21))"))
        (await! #(= 1 (count @handles)))
        (let [old-emit (:emit! (first @handles))
              old-run (:run-id (session/session-identity (host/current-session boundary)))]
          (is (= :accepted (old-emit [1 10])))
          (host/step! boundary)
          (is (= 10 (observed (host/current-session boundary) 'result)))
          (host/rerun! boundary (java.util.UUID/randomUUID))
          (await! #(= 2 (count @handles)))
          (is (= 1 @stops))
          (is (not= old-run (:run-id (session/session-identity (host/current-session boundary)))))
          (is (= :closed (old-emit [2 99])))
          (is (value/nothing? (observed (host/current-session boundary) 'result)))
          (is (= :accepted ((:emit! (second @handles)) [1 20])))
          (is (value/nothing? (observed (host/current-session boundary) 'result)))
          (host/step! boundary)
          (is (= 20 (observed (host/current-session boundary) 'result)))))
      (finally (host/close! boundary)))
    (is (= 2 @stops))))

(deftest native-factories-receive-explicit-interpreter-injection
  (let [boundary (host/open! (session/create-session) {:auto-advance? false})]
    (try
      (let [interpreter (host/effect-interpreter boundary)
            entry :propagators.compiler-2.fixtures.injected-module/bindings
            options {:id :injected-module
                     :files {"injected.lain"
                             (str "(load-primitive-environment "
                                  "\"test/propagators/compiler_2/fixtures/injected_module.clj\" "
                                  entry " 1) (define result (module-later 10))")}
                     :factory-arguments {entry [interpreter]}}
            prepared (files/prepare-extension! options)]
        (host/extend! boundary prepared)
        (await! #(= 1 (host/pending-count boundary)))
        (is (value/nothing? (observed (host/current-session boundary) 'result)))
        (host/step! boundary)
        (is (= 11 (observed (host/current-session boundary) 'result)))
        (let [before (host/current-session boundary)
              same (files/prepare-extension! options)]
          (is (identical? before (session/extend-session before same)))
          (is (thrown? clojure.lang.ExceptionInfo
                       (session/extend-session before
                         (files/prepare-extension!
                           (assoc options :factory-arguments
                                  {entry [(host/effect-interpreter boundary)]})))))
          (is (= 11 (observed (host/current-session boundary) 'result))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"argument vectors"
              (files/prepare-extension! (assoc options :factory-arguments {entry interpreter})))))
      (finally (host/close! boundary)))))
