(ns gx10-benchmark.controller
  "Interpret the Lain lifecycle with explicit effects. Results precede admission."
  (:require [clojure.java.io :as io]
            [gx10-benchmark.journal :as journal]
            [gx10-benchmark.outcome :as outcome]
            [gx10-benchmark.provenance :as provenance]
            [gx10-benchmark.session :as session]))

(def default-lock (str (System/getProperty "java.io.tmpdir") "/gx10-benchmark.lock"))
(def expected-stages
  [:cleanup-before :verify-unloaded :settle-memory :check-budget :load-model
   :verify-configuration :preload :execute :evaluate :save-result :cleanup-after])

(defn cell [model task configuration]
  (when-not (and (string? model) (seq model) (string? task) (seq task)
                 (map? configuration))
    (throw (ex-info "Cell requires a model, task and explicit configuration" {})))
  {:model model :task task :configuration configuration})

(defn- dispatch! [effects stage context]
  (let [handler (get effects stage)]
    (when-not (fn? handler)
      (throw (ex-info "Missing effect implementation" {:stage stage})))
    (let [receipt (handler context)]
      (when-not (and (map? receipt) (true? (:ok? receipt)))
        (throw (ex-info "Lifecycle operation failed"
                        {:stage stage :receipt receipt
                         :outcome (:outcome receipt :runtime-error)})))
      (when (and (#{:cleanup-before :verify-unloaded :settle-memory
                    :verify-configuration :cleanup-after} stage)
                 (not (true? (:verified? receipt))))
        (throw (ex-info "Lifecycle gate lacks verified evidence" {:stage stage :receipt receipt})))
      (when (and (= :check-budget stage) (not (true? (:approved? receipt))))
        (throw (ex-info "Clean memory budget declined admission"
                        {:stage stage :outcome :setup-declined :receipt receipt})))
      receipt)))

(defn- save-event! [directory events event]
  (swap! events conj (assoc event :epoch-ms (System/currentTimeMillis)))
  (journal/write-record! (io/file directory "events.edn") @events))

(defn- assessed-result [state]
  (outcome/result (:execution state) (:evaluation state)
                  (:workflow state {}) (:timing state {})))

(defn- attempt! [cell directory effects plan]
  (let [root (journal/new-directory! directory)
        events (atom [])
        paths (into {} (for [name [:workspace :agent :history :web-cache :tmp]]
                         [name (.getPath (journal/new-directory! (io/file root (clojure.core/name name))))]))
        state (atom {:cell cell :directory (.getPath root) :paths paths})
        checkpoint (io/file root "checkpoint.edn")
        result-path (io/file root "result.edn")]
    (journal/write-record! (io/file root "cell.edn") cell)
    (journal/write-record! (io/file root "provenance.edn") (provenance/capture cell))
    (journal/write-record! checkpoint {:stage :created})
    (try
      (doseq [stage (remove #{:cleanup-after} plan)]
        (journal/write-record! checkpoint {:stage stage})
        (save-event! root events {:event :started :stage stage})
        (let [started (System/nanoTime)
              receipt (if (= :save-result stage)
                        (let [result (assoc (assessed-result @state) :durable? true
                                            :cleanup-verified? false)]
                          (journal/write-record! result-path result)
                          {:ok? true :result result})
                        (dispatch! effects stage @state))]
          (swap! state merge (dissoc receipt :ok?))
          (save-event! root events {:event :finished :stage stage :receipt receipt
                                   :duration-ms (/ (- (System/nanoTime) started) 1e6)})))
      (catch Throwable error
        (let [terminal (cond
                         (instance? InterruptedException error) :interrupted
                         (#{:interrupted :resource-stopped :transition-failed :setup-declined}
                          (:outcome (ex-data error))) (:outcome (ex-data error))
                         :else :runtime-error)
              failure {:outcome terminal
                       :execution terminal :correct? false :durable? true
                       :cleanup-verified? false :error (ex-message error)
                       :error-data (ex-data error)}]
          (swap! state assoc :result failure)
          (journal/write-record! result-path failure)
          (save-event! root events {:event :failure :error (ex-message error)})))
      (finally
        (journal/write-record! checkpoint {:stage :cleanup-after})
        (try
          (let [receipt (dispatch! effects :cleanup-after @state)]
            (when-not (true? (:verified? receipt))
              (throw (ex-info "Cleanup returned without verification" {:receipt receipt})))
            (swap! state update :result assoc :cleanup-verified? true)
            (save-event! root events {:event :cleanup-verified :receipt receipt}))
          (catch Throwable error
            (swap! state update :result assoc :cleanup-verified? false
                   :cleanup-error (ex-message error))
            (save-event! root events {:event :cleanup-failed :error (ex-message error)})))
        (journal/write-record! result-path (:result @state))
        (journal/write-record! checkpoint {:stage :complete
                                           :cleanup-verified? (get-in @state [:result :cleanup-verified?])})))
    (:result @state)))

(defn- validated-plan []
  (let [plan (session/lifecycle)]
    (when-not (= expected-stages plan)
      (throw (ex-info "Lain lifecycle violates the admission/persistence contract" {:plan plan})))
    plan))

(defn run-cell!
  "NEW_DIRECTORY is immutable attempt history; callers cannot reuse it."
  [cell directory effects]
  (let [plan (validated-plan)]
    (journal/with-host-lock! default-lock #(attempt! cell directory effects plan))))

(defn- logical-cell! [cell directory effects plan]
  (let [root (journal/new-directory! directory)]
    (loop [number 1]
      (let [result (attempt! cell (io/file root (str "attempt-" number)) effects plan)
            repeat? (outcome/reload-eligible? result)
            retry? (and repeat? (= 1 number))
            decision {:attempt number :reload? retry? :result result}]
        ;; Record the decision before permitting a new load/fresh Pi history.
        (journal/write-record! (io/file root "recovery.edn") decision)
        (if retry?
          (recur (inc number))
          (let [final (assoc result :reloads-used (dec number)
                             :reload-exhausted? (and repeat? (= 2 number))
                             :timing-valid? (and (= 1 number) (:timing-valid? result)))]
            (journal/write-record! (io/file root "result.edn") final)))))))

(defn run-queue!
  "Sequential cells; a verified model repetition stop may exhaust one reload.
   Unknown failures, interruption and uncertain cleanup stop scheduling."
  [cells directory effects]
  (let [plan (validated-plan)]
    (journal/with-host-lock!
     default-lock
     (fn []
       (let [root (journal/new-directory! directory)]
         (loop [remaining (seq cells) results []]
           (if-let [cell (first remaining)]
             (let [result (logical-cell! cell (io/file root (str "cell-" (count results))) effects plan)
                   updated (conj results result)
                   continue? (session/continue-queue? (outcome/queue-facts result))]
               (journal/write-record! (io/file root "queue.edn")
                                      {:stage (if continue? :running :stopped)
                                       :completed (count updated) :results updated})
               (if continue? (recur (next remaining) updated) updated))
             (do (journal/write-record! (io/file root "queue.edn")
                                         {:stage :finished :completed (count results) :results results})
                 results))))))))
