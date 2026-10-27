(ns propagators.compiler-2-harness-effect-boundary-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [examples.lain.subagents.effect-boundary :as boundary]
            [examples.lain.subagents.facts :as facts])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(def session "effect-test-session")
(defn request
  ([] (request "send-a"))
  ([id] (facts/fact session id 0 :socket/send {:payload {:text "hello"}})))
(defn spawn [f]
  (let [result (promise)
        thread (doto (Thread. ^Runnable #(deliver result (try {:ok (f)} (catch Throwable error {:error error}))))
                 (.setDaemon true) (.start))]
    {:result result :thread thread}))
(defn result! [task]
  (let [outcome (deref (:result task) 5000 ::timeout)]
    (when (= ::timeout outcome) (throw (ex-info "Controlled effect did not finish" {})))
    (when-let [error (:error outcome)] (throw error))
    (:ok outcome)))
(defn temp-directory []
  (.toFile (Files/createTempDirectory "lain-effects-" (make-array FileAttribute 0))))
(defn remove-directory! [directory]
  (doseq [file (reverse (file-seq directory))] (io/delete-file file true)))

(deftest duplicate-and-concurrent-dispatch-share-one-claim-and-stamped-result
  (let [calls (atom 0)
        started (promise)
        release (java.util.concurrent.Semaphore. 0)
        outside (boundary/open!
                 session
                 {:mode :synthetic
                  :invoke! (fn [effect]
                             (if (= :dispose (:operation effect))
                               {:observations [] :receipt {:cleanup-verified true}}
                               (do (swap! calls inc) (deliver started true)
                                   (.acquireUninterruptibly release)
                                   [{:kind :socket/sent :written ["client-a"]}])))} )
        first-call (spawn #(boundary/dispatch! outside (request) 10))]
    (try
      (is (= true (deref started 5000 ::timeout)))
      (let [duplicate (spawn #(boundary/dispatch! outside (request) 999))]
        (is (= ::waiting (deref (:result duplicate) 100 ::waiting)))
        (.release release)
        (let [first-result (result! first-call)]
          (is (= first-result (result! duplicate)))
          (is (= first-result (boundary/dispatch! outside (request) 1000)))
          (is (= [10] (mapv :order (facts/ordered-facts first-result))))))
      (is (= 1 @calls))
      (is (= :completed (:status (get (boundary/receipts outside) "send-a"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Conflicting"
                            (boundary/dispatch! outside (assoc (request) :payload {:text "different"}) 20)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"session"
                            (boundary/dispatch! outside (assoc (request) :session "foreign") 20)))
      (finally (.release release) (boundary/close! outside)))
    (is (true? (:cleanup-verified (boundary/close! outside))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disposed"
                          (boundary/dispatch! outside (request) 30)))))

(deftest observe-and-replay-never-invoke-the-outside-handler
  (doseq [mode [:observe :replay]]
    (let [calls (atom 0)
          outside (boundary/open! session {:mode mode :invoke! (fn [_] (swap! calls inc) [])})]
      (is (empty? (facts/ordered-facts (boundary/dispatch! outside (request) 0))))
      (is (empty? (boundary/receipts outside)))
      (is (true? (:cleanup-verified (boundary/close! outside))))
      (is (= 0 @calls)))))

(deftest partial-write-is-an-inspectable-uncertain-outcome-and-never-retried
  (let [calls (atom 0)
        outside (boundary/open!
                 session
                 {:mode :synthetic
                  :invoke! (fn [effect]
                             (if (= :dispose (:operation effect)) []
                                 (do (swap! calls inc)
                                     (throw (ex-info "Second socket write failed"
                                                     {:status :partial-write :written ["client-a"]})))))} )]
    (try
      (let [result (boundary/dispatch! outside (request) 5)
            receipt (get (boundary/receipts outside) "send-a")]
        (is (= [:effect/failed] (mapv :kind (facts/ordered-facts result))))
        (is (= :partial-write (:status receipt)))
        (is (= ["client-a"] (get-in receipt [:receipt :written])))
        (is (= result (boundary/dispatch! outside (request) 50)))
        (is (= 1 @calls)))
      (finally (boundary/close! outside)))))

(deftest disposal-unblocks-and-joins-an-already-claimed-effect
  (let [started (promise)
        release (java.util.concurrent.Semaphore. 0)
        disposals (atom 0)
        outside (boundary/open!
                 session
                 {:mode :synthetic
                  :invoke! (fn [effect]
                             (if (= :dispose (:operation effect))
                               (do (swap! disposals inc) (.release release)
                                   {:observations [] :receipt {:cleanup-verified true}})
                               (do (deliver started true) (.acquireUninterruptibly release)
                                   [{:kind :socket/sent}])))} )
        dispatched (spawn #(boundary/dispatch! outside (request) 2))]
    (is (= true (deref started 5000 ::timeout)))
    (is (true? (:cleanup-verified (boundary/close! outside))))
    (is (= [:socket/sent] (mapv :kind (facts/ordered-facts (result! dispatched)))))
    (boundary/close! outside)
    (is (= 1 @disposals))))

(deftest callback-observations-cannot-forge-session-or-event-identity
  (let [calls (atom 0)
        outside (boundary/open!
                 session {:mode :synthetic
                          :invoke! (fn [effect]
                                     (if (= :dispose (:operation effect)) []
                                         (do (swap! calls inc)
                                             [{:kind :socket/sent :id "forged"}])))} )]
    (try
      (let [failed (boundary/dispatch! outside (request) 0)]
        (is (= [:effect/failed] (mapv :kind (facts/ordered-facts failed))))
        (is (= :uncertain (:status (get (boundary/receipts outside) "send-a"))))
        (is (= failed (boundary/dispatch! outside (request) 10)))
        (is (= 1 @calls)))
      (finally (boundary/close! outside)))))

(deftest failed-cleanup-is-preserved-and-disposal-is-idempotent
  (let [disposals (atom 0)
        outside (boundary/open!
                 session {:mode :synthetic
                          :invoke! (fn [_] (swap! disposals inc)
                                     {:observations [] :receipt {:cleanup-verified false}})})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cleanup"
                          (boundary/close! outside)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cleanup"
                          (boundary/close! outside)))
    (is (= 1 @disposals))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disposed"
                          (boundary/dispatch! outside (request) 0)))))

(defrecord InternalGraph [hidden])
(deftest plain-data-bounds-reject-internal-graphs-before-traversal
  (let [walks (atom 0)
        graph (->InternalGraph (lazy-seq (swap! walks inc) [1]))
        seqable (reify clojure.lang.Seqable (seq [_] (swap! walks inc) (seq [1])))]
    (is (= {:allowed [nil true 1 0.5 :status "text"]}
           (boundary/bounded-data! {:allowed [nil true 1 0.5 :status "text"]})))
    (doseq [invalid [graph seqable (facts/empty-log session)
                     :bool4/nothing :bool4/contradiction
                     Double/NaN Double/POSITIVE_INFINITY
                     (vec (repeat 4097 1)) (apply str (repeat 65537 "x"))
                     (vec (repeat 17 (apply str (repeat 65536 "x"))))
                     (nth (iterate vector 0) 13) {1 :numeric-key}]]
      (is (thrown? clojure.lang.ExceptionInfo (boundary/bounded-data! invalid))))
    (is (= 0 @walks))))

(deftest private-recording-replays-only-observations-without-invocation
  (let [directory (temp-directory)
        path (str (io/file directory "receipts.edn"))
        calls (atom 0)
        outside (boundary/open!
                 session {:mode :synthetic :recording path
                          :invoke! (fn [effect]
                                     (if (= :dispose (:operation effect)) []
                                         (do (swap! calls inc) [{:kind :harbor/context :assignment-id "a"
                                                               :context {:trial-id "a"}}])))})]
    (try
      (let [observed (boundary/dispatch! outside (request) 7)]
        (is (= (PosixFilePermissions/fromString "rw-------")
               (Files/getPosixFilePermissions (.toPath (io/file path)) (make-array java.nio.file.LinkOption 0))))
        (is (thrown? Exception (boundary/open! session {:mode :synthetic :recording path :invoke! (fn [_] [])})))
        (boundary/close! outside)
        (is (= observed (boundary/read-recording path session)))
        (let [replay (boundary/open! session {:mode :replay :recording path
                                              :invoke! (fn [_] (swap! calls inc) [])})]
          (try
            (is (= observed (boundary/dispatch! replay (request) 1000)))
            (finally (boundary/close! replay))))
        (is (= 1 @calls))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"foreign"
                              (boundary/read-recording path "another-session"))))
      (finally (boundary/close! outside) (remove-directory! directory)))))

(deftest interrupted-durable-intent-cannot-be-automatically-executed-on-reopen
  (let [directory (temp-directory)
        path (str (io/file directory "interrupted.edn"))
        marker (str (io/file directory "invoked"))
        child-log (io/file directory "child.log")
        java (str (io/file (System/getProperty "java.home") "bin" "java"))
        source (str "(require '[examples.lain.subagents.effect-boundary :as b]"
                    " '[examples.lain.subagents.facts :as f])"
                    "(let [outside (b/open! \"" session "\" {:mode :synthetic :recording " (pr-str path)
                    " :invoke! (fn [_] (spit " (pr-str marker) " \"invoked\") (Thread/sleep 60000) [])})]"
                    "(b/dispatch! outside (f/fact \"" session "\" \"interrupted\" 0 :socket/send {}) 1))")
        builder (doto (ProcessBuilder. (into-array String [java "-cp" (System/getProperty "java.class.path")
                                                          "clojure.main" "-e" source]))
                  (.redirectErrorStream true) (.redirectOutput child-log))
        _ (.put (.environment builder) "JAVA_TOOL_OPTIONS" "-Xmx128m")
        child (.start builder)]
    (try
      (let [deadline (+ (System/nanoTime) 15000000000)]
        (loop []
          (when (and (not (.exists (io/file marker))) (.isAlive child) (< (System/nanoTime) deadline))
            (Thread/sleep 20) (recur))))
      (is (.exists (io/file marker)) "The child persisted its intent before entering the callback")
      (.destroyForcibly child)
      (.waitFor child 5 java.util.concurrent.TimeUnit/SECONDS)
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"reconciliation"
           (boundary/open! session {:mode :synthetic :recording path :invoke! (fn [_] [])})))
      (is (empty? (facts/ordered-facts (boundary/read-recording path session))))
      (finally
        (.destroyForcibly child)
        (.waitFor child 5 java.util.concurrent.TimeUnit/SECONDS)
        (remove-directory! directory)))))
