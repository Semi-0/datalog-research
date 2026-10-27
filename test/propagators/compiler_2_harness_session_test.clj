(ns propagators.compiler-2-harness-session-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.harness-session :as harness]
            [examples.lain.subagents.effect-boundary :as effects]
            [examples.lain.subagents.pi-transport :as pi-transport]
            [examples.lain.subagents.socket-transport :as socket-transport]
            [graph.json :as json])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.net Socket]))

(defn fixtures
  ([identity running?] (fixtures identity running? :pi-better-subagents))
  ([identity running? plugin]
  (let [calls (atom [])
        writes (atom [])
        config {:plugin plugin :version (pi-transport/pins plugin)
                :provider "ollama" :model "controlled-fixture"
                :cwd (System/getProperty "user.dir") :tools ["read"]}
        call! (fn [request]
                (swap! calls conj request)
                (case (:tool request)
                  "subagents_enable" {:enabled true}
                  "subagent"
                  (case (get-in request [:arguments :action])
                    nil {:status :launched :child (str "child/" (get-in request [:arguments :task]))}
                    "status" (if @running?
                               {:status :running}
                               {:status :completed :child (get-in request [:arguments :id]) :result {:answer "nonce"}})
                    "stop" {:status :stopped :child (get-in request [:arguments :id]) :cleanup-verified true})
                  "subagent_spawn_batch"
                  {:status :launched :child (str "child/" (get-in request [:arguments :tasks 0 :task]))}
                  "subagent_result"
                  (if @running?
                    {:status :running}
                    {:status :completed :child (get-in request [:arguments :id]) :result {:answer "nonce"}})
                  "subagent_stop"
                  {:status :stopped :child (get-in request [:arguments :id]) :cleanup-verified true}
                  (throw (ex-info "Unexpected controlled tool" {:tool (:tool request)}))))
        options
        {:mode :synthetic :pi-config config
         :pi-hooks {:call! call!
                    :reconcile! (fn [_] (throw (ex-info "No uncertain launch expected" {})))
                    :dispose! (fn [_] (swap! calls conj {:operation :dispose}) {:cleanup-verified true})}
         :harbor-callbacks
         {:prepare (fn [{:keys [assignment-id]}]
                     {:trial-id assignment-id :instructions "Return the nonce"
                      :private-grading-metadata "must not reach child"})
          :grade (fn [request]
                   (swap! calls conj {:operation :grade :assignment-id (:assignment-id request)})
                   {:passed (= {:answer "nonce"} (:result request)) :assertions 1})
          :dispose! (fn [_] {:cleanup-verified true})}
         :socket-invoke! (fn [request]
                           (if (= :dispose (:operation request))
                             {:observations [] :receipt {:cleanup-verified true}}
                             (do (swap! writes conj (:payload request))
                                 {:observations [] :receipt {:recorded true}})))}]
    {:identity identity :calls calls :writes writes :options options})))

(defn submit! [owner id order kind fields]
  (harness/observe! owner :socket
                     (facts/from-facts (:session (harness/artifacts owner))
                                       [(facts/fact (:session (harness/artifacts owner)) id order kind fields)])))

(defn launch-calls [calls]
  (filter #(or (= "subagent_spawn_batch" (:tool %))
               (and (= "subagent" (:tool %)) (nil? (get-in % [:arguments :action])))) @calls))

(deftest composed-extension-runs-one-controlled-trial-and-deduplicates-effects
  (doseq [plugin [:pi-better-subagents :pi-subagents]]
   (let [identity (str "composed/" (name plugin))
        {:keys [calls writes options]} (fixtures identity (atom false) plugin)
        owner (harness/open! identity options)]
    (try
      (submit! owner "submit-a" 0 :assignment/submitted {:assignment-id "a" :task "a"})
      (is (some #(and (= :assignment/submitted (:kind %)) (= "a" (:assignment-id %)))
                (facts/ordered-facts (harness/facts-at owner :assignments))))
      (harness/pump! owner)
      (is (= 1 (count (launch-calls calls))))
      (is (= 1 (count (filter #(= :grade (:operation %)) @calls))))
      (is (some #(= :assignment/assessed (:kind %))
                (facts/ordered-facts (harness/facts-at owner :assignments))))
      (is (some #(= :runtime/completed (:kind %))
                (facts/ordered-facts (harness/facts-at owner :results))))
      (is (seq @writes))
      (let [before [(count @calls) (count @writes)]]
        (submit! owner "submit-a" 0 :assignment/submitted {:assignment-id "a" :task "a"})
        (is (= {:status :quiescent :steps 0} (harness/pump! owner)))
        (is (= before [(count @calls) (count @writes)])))
      (is (every? #(not (contains? (:assignment-context %) :private-grading-metadata))
                  (launch-calls calls)))
      (let [artifacts (harness/artifacts owner)]
        (is (= artifacts (effects/bounded-data! artifacts)))
        (is (= identity (:session (json/read-json (json/write-json artifacts))))))
      (finally
        (let [closed (harness/close! owner)]
          (is (= :closed (:status closed)))
          (is (= closed (harness/close! owner)))))))))

(deftest reorder-does-not-replace-owned-work-and-cancellation-is-confirmed
  (let [running? (atom true)
        {:keys [calls options]} (fixtures "cancel" running?)
        owner (harness/open! "cancel" options)]
    (try
      (submit! owner "submit-a" 0 :assignment/submitted {:assignment-id "a" :task "a"})
      (submit! owner "submit-b" 1 :assignment/submitted {:assignment-id "b" :task "b"})
      (harness/pump! owner)
      (is (= 1 (count (launch-calls calls))))
      (is (= "a" (get-in (first (launch-calls calls)) [:arguments :tasks 0 :task])))
      (submit! owner "reorder" 100 :assignment/reordered {:assignment-ids ["b" "a"]})
      (harness/pump! owner)
      (is (= 1 (count (launch-calls calls))) "Reorder cannot relaunch or replace active work")
      (submit! owner "cancel-a" 101 :assignment/cancel-requested {:assignment-id "a"})
      (harness/pump! owner)
      (is (some #(= "subagent_stop" (:tool %)) @calls))
      (is (some #(and (= :runtime/cancelled (:kind %)) (= "a" (:assignment-id %)))
                (facts/ordered-facts (harness/facts-at owner :results))))
      (is (= 2 (count (launch-calls calls))))
      (reset! running? false)
      (harness/poll! owner "finish-b")
      (is (= ["b"] (mapv :assignment-id (filter #(= :grade (:operation %)) @calls))))
      (finally (harness/close! owner)))))

(deftest failed-effects-and-cleanup-are-retained-and-not-retried
  (let [{:keys [options]} (fixtures "failed" (atom false))
        prepares (atom 0) disposals (atom 0)
        owner (harness/open!
               "failed"
               (-> options
                   (assoc-in [:harbor-callbacks :prepare]
                             (fn [_] (swap! prepares inc) (throw (ex-info "controlled preparation failure" {}))))
                   (assoc-in [:pi-hooks :dispose!]
                             (fn [_] (swap! disposals inc) {:cleanup-verified false}))))
        error-of (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo error error)))]
    (submit! owner "submit-a" 0 :assignment/submitted {:assignment-id "a" :task "a"})
    (dotimes [_ 2]
      (let [error (error-of #(harness/pump! owner))]
        (is (= :harbor (get-in (ex-data error) [:failures 0 :boundary])))))
    (is (= 1 @prepares))
    (let [artifacts (harness/artifacts owner)]
      (is (some #(some (fn [event] (= :effect/failed (:kind event))) (:observations %))
                (:harbor-receipts artifacts)))
      (is (= artifacts (effects/bounded-data! artifacts))))
    (let [original-close socket-transport/close!
          callback-result (atom nil)
          first-error
          (with-redefs [socket-transport/close!
                        (fn [boundary]
                          ;; Joining a client callback must not retain the session
                          ;; lock: late callbacks must reach require-open! promptly.
                          (let [callback (future
                                           (try (harness/observe! owner :socket (facts/empty-log "failed"))
                                                :unexpected-open
                                                (catch clojure.lang.ExceptionInfo _ :closed)))]
                            (reset! callback-result (deref callback 1000 :blocked)))
                          (original-close boundary))]
            (error-of #(harness/close! owner)))
          repeated-error (error-of #(harness/close! owner))]
      (is (= :closed @callback-result))
      (is (instance? clojure.lang.ExceptionInfo first-error))
      (is (identical? first-error repeated-error))
      (is (= 1 @disposals))
      (is (= :failed (get-in (harness/artifacts owner) [:close-result :status])))
      (is (thrown? clojure.lang.ExceptionInfo (harness/pump! owner)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (harness/observe! owner :socket (facts/empty-log "failed")))))))

(deftest real-loopback-user-socket-feeds-the-same-network
  (let [{:keys [options]} (fixtures "wire" (atom false))
        owner (harness/open! "wire" (-> options
                                        (dissoc :socket-invoke!)
                                        (assoc :socket-options {:mode :live :read-timeout-ms 60000})))]
    (try
      (with-open [socket (Socket. "127.0.0.1" (int (harness/socket-port owner)))
                  reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))
                  writer (OutputStreamWriter. (.getOutputStream socket))]
        (.setSoTimeout socket 30000)
        (.write writer (str (json/write-json {:session "wire" :id "wire-submit" :order 0
                                             :kind "assignment/submitted" :assignment-id "a" :task "a"}) "\n"))
        (.flush writer)
        (is (= "received" (:status (json/read-json (.readLine reader)))))
        (let [deadline (+ (System/nanoTime) 30000000000)]
          (loop []
            (when-not (seq (facts/ordered-facts (harness/facts-at owner :socket-in)))
              (when (> (System/nanoTime) deadline) (throw (ex-info "Socket publication timed out" {})))
              (Thread/sleep 10)
              (recur))))
        (harness/pump! owner)
        (let [feedback (json/read-json (.readLine reader))]
          (is (= "wire" (:session feedback)))
          (is (string? (:kind feedback))))
        (is (some #(= :runtime/completed (:kind %))
                  (facts/ordered-facts (harness/facts-at owner :results)))))
      (finally (harness/close! owner)))))
