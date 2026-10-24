(ns graph.lain-editor.experiments.stack-benchmark
  "Measured retained-network versus clean-reconstruction experiment for r9."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [graph.lain-editor.stack.index :as constraint-index]
            [graph.lain-editor.development.session :as development-session]
            [graph.lain-editor.experiments.baseline :as baseline]
            [graph.lain-editor.experiments.comparison :as comparison]
            [graph.lain-editor.experiments.stack-experiment :as experiment]
            [graph.lain-editor.stack.scene :as scene]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.inspection.retraction :as retraction]
            [propagators.compiler-2.runtime.tui.block-model :as block-model]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net])
  (:import [java.time Instant]
           [java.util UUID]))

(def fixture-id :distinct-calls)
(def fixture-binding 'benchmark-capture)
(def unrelated-index (inc (baseline/stack-query-index)))
(def reader-index 5)
(def sample-limit-ns (* 60 1000000000))

(defn- fixture-extension []
  (runtime/extension-bundle
   {:id :graph.lain-editor.stack-benchmark/benchmark-capture
    :bindings [[fixture-binding (experiment/fixture-capture fixture-id)]]
    :effects []}))

(defn- new-fixture-session []
  (let [session (development-session/new-session)]
    (runtime/install-session-extension! session (fixture-extension) {})
    session))

(defn- query-commit []
  {:commit-id "30000000-0000-0000-0000-000000000008"
   :client-id baseline/client-id
   :index (baseline/stack-query-index)
   :expected-version nil
   :text "(compound->stack benchmark-capture (root-compound-node benchmark-capture))"})

(defn- unrelated-commit []
  {:commit-id "31000000-0000-0000-0000-000000000008"
   :client-id baseline/client-id
   :index unrelated-index
   :expected-version nil
   :text "(define benchmark-unrelated :initial)"})

(defn fixture-instance [implemented?]
  {:schema "compiler-2/versioned-instance"
   :schema-version 1
   :clients [{:client-id baseline/client-id :mode :versioned-premise}]
   :commits (vec (concat (baseline/initial-commits)
                         (when implemented? (baseline/implementation-commits))
                         [(query-commit) (unrelated-commit)]))})

(defn- display-result [state]
  (let [block (block-model/block-by-index
               state baseline/client-id (inc (baseline/stack-query-index)))
        display-id (:display-id block)]
    (data/payload
     (net/network-cell-strongest (:program/net state) display-id))))

(defn- semantic-result [session]
  (let [result (display-result @session)]
    (cond
      (value/contradiction? result) {:status :contradictory}
      (value/nothing? result) {:status :pending}
      :else
      (let [compiled (scene/compile-scene (:program/net @session) result)]
        {:status :satisfied
         :occurrence-identities (mapv :identity (:planes compiled))
         :diagnostics (:diagnostics compiled)
         :scene (comparison/normalize-stack-scene compiled)}))))

(defn- retained-counts [session]
  (let [network (:program/net @session)]
    {:entries (count (net/net-env network))
     :nodes (count (net/net-graph network))}))

(defn- current-record [session index]
  (-> (block-model/block-by-index @session baseline/client-id index)
      :version-history vec peek))

(defn- current-version [session index]
  (:version (current-record session index)))

(defn- request [session index text]
  {:commit-id (str (UUID/randomUUID))
   :client-id baseline/client-id
   :index index
   :expected-version (current-version session index)
   :text text})

(def ^:private constraint-propagators
  {'stack.raw:stack-shape? :stack/shape
   'stack.raw:scope-content? :stack/scope-content
   'stack.raw:direct-expansions? :stack/direct-expansions
   'stack.raw:reference-integrity? :stack/reference-integrity})

(defn- constraint-id-for [propagator-name]
  (get constraint-propagators propagator-name))

(defn- activation-summary [{:keys [activations elapsed-ns truncated? events]}]
  (let [queued (mapv :queued-after events)]
    {:propagator-activations activations
     :activation-elapsed-ns elapsed-ns
     :truncated? truncated?
     :queue-transitions (count events)
     :maximum-pending-work (reduce max 0 queued)
     :changed-output-count (reduce + 0 (map (comp count :output-changed-ids) events))
     :constraint-evaluations
     (->> events
          (keep (comp constraint-id-for :propagator/name))
          frequencies
          (sort-by (comp str key))
          (into (sorted-map)))}))

(defn- import-sample [instance]
  (let [session (new-fixture-session)
        started (System/nanoTime)]
    (try
      (let [receipt (runtime/import-instance! session instance)
            elapsed (- (System/nanoTime) started)]
        {:elapsed-ns elapsed
         :within-limit? (<= elapsed sample-limit-ns)
         :receipt (select-keys receipt [:status :commit-count])
         :semantic-result (semantic-result session)
         :retained (retained-counts session)})
      (catch Throwable error
        {:elapsed-ns (- (System/nanoTime) started)
         :within-limit? false
         :error {:class (str (class error)) :message (ex-message error)}})
      (finally
        (runtime/stop-clocks! session)))))

(defn- incremental-sample! [session commit instrument?]
  (runtime/drain-temperature! session)
  (let [started (System/nanoTime)
        captured
        (if instrument?
          (retraction/capture-commit
           (:program/net @session)
           #(runtime/commit-version! session commit))
          {:result (runtime/commit-version! session commit)})
        elapsed (- (System/nanoTime) started)]
    (cond->
     {:elapsed-ns elapsed
      :within-limit? (<= elapsed sample-limit-ns)
      :receipt (select-keys (:result captured)
                            [:status :index :version :replayed?])
      :runtime-profile (:runtime/last-incremental-profile @session)
      :temperature (runtime/drain-temperature! session)
      :semantic-result (semantic-result session)
      :retained (retained-counts session)}
      instrument? (assoc :work (activation-summary (:profile captured))))))

(defn- compact-sample [sample]
  (update sample :semantic-result #(select-keys % [:status])))

(defn- sample-pair! [session scenario ordinal phase]
  (let [index (case scenario :unrelated unrelated-index :implementation reader-index)
        text (case scenario
               :unrelated
               (pr-str (list 'define 'benchmark-unrelated
                             (keyword (str (name phase) "-" ordinal))))
               :implementation
               (pr-str (nth (baseline/implementation-forms) reader-index)))
        commit (request session index text)
        clean-first? (odd? ordinal)
        prospective (update (runtime/export-instance @session) :commits conj commit)
        clean-before (when clean-first? (import-sample prospective))
        incremental (incremental-sample! session commit true)
        clean (or clean-before
                  (import-sample (runtime/export-instance @session)))
        agrees? (= (:semantic-result incremental) (:semantic-result clean))]
    {:ordinal ordinal
     :phase phase
     :order (if clean-first? :clean-first :incremental-first)
     :incremental (compact-sample incremental)
     :clean (compact-sample clean)
     :semantic-agreement? agrees?}))

(defn- scenario-run [scenario]
  (let [session (new-fixture-session)]
    (try
      (runtime/import-instance! session (fixture-instance true))
      (let [samples (mapv #(sample-pair! session scenario %
                                        (if (< % 3) :warmup :measured))
                          (range 8))]
        {:scenario scenario
         :warmups (subvec samples 0 3)
         :measurements (subvec samples 3)})
      (finally
        (runtime/stop-clocks! session)))))

(defn- outcome [status dependencies]
  (cond-> {:constraint/id fixture-id
           :specification/revision baseline/specification-revision
           :status status :required? true
           :reason (case status
                     :satisfied :all-declared-conditions-hold
                     :pending :source-or-implementation-incomplete
                     :contradictory :conflicting-source-facts
                     :violated :declared-condition-failed)
           :dependencies (vec dependencies)}
    (= :satisfied status) (assoc :support (set dependencies))))

(defn- evidence [dependencies]
  {:evidence/id :fixture/distinct-calls
   :constraint/id fixture-id
   :status :supported
   :dependencies (vec dependencies)})

(defn- result-status [session]
  (:status (semantic-result session)))

(defn- record-edit! [session label index text instrument?]
  (let [previous-record (current-record session index)
        previous (:premise-id previous-record)
        commit (request session index text)
        sample (incremental-sample! session commit instrument?)]
    {:edit label
     :block-index index
     :from-version (:version previous-record)
     :to-version (get-in sample [:receipt :version])
     :graph.lain-editor.stack-benchmark/previous-premise previous
     :sample (compact-sample sample)}))

(defn- portable-edit [edit]
  (dissoc edit :graph.lain-editor.stack-benchmark/previous-premise))

(defn- portable-outcome [record index version]
  (let [coordinate [:implementation baseline/client-id index version]]
    (cond-> (assoc record :dependencies [coordinate])
      (contains? record :support) (assoc :support #{coordinate}))))

(defn- edit-sequence-run []
  (let [session (new-fixture-session)]
    (try
      (runtime/import-instance! session (fixture-instance false))
      (let [initial {:edit :initial-pending :status (result-status session)}
            installs
            (mapv (fn [commit]
                    {:edit :install-reader
                     :index (:index commit)
                     :sample (compact-sample
                              (incremental-sample! session commit true))})
                  (baseline/implementation-commits))
            reader-premise (:premise-id (current-record session reader-index))
            supported-outcome (outcome :satisfied [reader-premise])
            index0 (constraint-index/create
                    baseline/specification-revision [supported-outcome] true
                    [(evidence [reader-premise])])
            unrelated (record-edit!
                       session :unrelated-edit unrelated-index
                       "(define benchmark-unrelated :changed)" true)
            unrelated-invalidation
            (constraint-index/invalidate-premise
             index0 (:graph.lain-editor.stack-benchmark/previous-premise unrelated))
            replacement (record-edit!
                         session :replace-reader reader-index
                         (pr-str (nth (baseline/implementation-forms) reader-index)) true)
            replacement-invalidation
            (constraint-index/invalidate-premise
             (:index unrelated-invalidation) (:graph.lain-editor.stack-benchmark/previous-premise replacement))
            replaced-premise (:premise-id (current-record session reader-index))
            index1 (constraint-index/apply-deltas
                    (:index replacement-invalidation)
                    {:outcome-updates [{:op :upsert
                                        :outcome (outcome (result-status session)
                                                          [replaced-premise])}]
                     :evidence-updates [{:op :upsert
                                         :record (evidence [replaced-premise])}]})
            withdrawal (record-edit!
                        session :withdraw-reader reader-index
                        (pr-str (nth (baseline/pending-implementation-forms)
                                     reader-index)) true)
            withdrawal-invalidation
            (constraint-index/invalidate-premise
             index1 (:graph.lain-editor.stack-benchmark/previous-premise withdrawal))
            index2 (constraint-index/apply-deltas
                    (:index withdrawal-invalidation)
                    {:outcome-updates [{:op :upsert
                                        :outcome (outcome (result-status session) [])}]})
            restore (record-edit!
                     session :restore-reader reader-index
                     (pr-str (nth (baseline/implementation-forms) reader-index)) true)
            restored-premise (:premise-id (current-record session reader-index))
            index3 (constraint-index/apply-deltas
                    index2 {:outcome-updates [{:op :upsert
                                               :outcome (outcome (result-status session)
                                                                 [restored-premise])}]
                            :evidence-updates [{:op :upsert
                                                :record (evidence [restored-premise])}]})
            before-failed (runtime/export-instance @session)
            failed (try
                     (runtime/commit-version!
                      session (assoc (request session reader-index
                                              (pr-str (nth (baseline/implementation-forms)
                                                           reader-index)))
                                     :expected-version 0))
                     {:accepted? true}
                     (catch clojure.lang.ExceptionInfo error
                       {:accepted? false :message (ex-message error)}))
            after-failed (runtime/export-instance @session)
            no-op (record-edit!
                   session :no-op-edit reader-index
                   (pr-str (nth (baseline/implementation-forms) reader-index)) true)
            no-op-invalidation
            (constraint-index/invalidate-premise
             index3 (:graph.lain-editor.stack-benchmark/previous-premise no-op))
            final-premise (:premise-id (current-record session reader-index))
            index4 (constraint-index/apply-deltas
                    (:index no-op-invalidation)
                    {:outcome-updates [{:op :upsert
                                        :outcome (outcome (result-status session)
                                                          [final-premise])}]
                     :evidence-updates [{:op :upsert
                                         :record (evidence [final-premise])}]})]
        {:steps [initial
                 {:edit :install-reader
                  :commits (count installs)
                  :status (get-in (last installs) [:sample :semantic-result :status])
                  :samples installs}
                 (assoc (portable-edit unrelated)
                        :constraint-evaluations []
                        :invalidated-outcomes (:invalidated-outcomes unrelated-invalidation)
                        :invalidated-evidence (:invalidated-evidence unrelated-invalidation))
                 (assoc (portable-edit replacement)
                        :constraint-evaluations [fixture-id]
                        :invalidated-outcomes (:invalidated-outcomes replacement-invalidation)
                        :invalidated-evidence (:invalidated-evidence replacement-invalidation))
                 (assoc (portable-edit withdrawal)
                        :constraint-evaluations [fixture-id]
                        :invalidated-outcomes (:invalidated-outcomes withdrawal-invalidation)
                        :invalidated-evidence (:invalidated-evidence withdrawal-invalidation))
                 (assoc (portable-edit restore)
                        :constraint-evaluations [fixture-id])
                 {:edit :failed-commit
                  :result failed
                  :ledger-unchanged? (= before-failed after-failed)}
                 (assoc (portable-edit no-op)
                        :constraint-evaluations [fixture-id]
                        :invalidated-outcomes (:invalidated-outcomes no-op-invalidation)
                        :invalidated-evidence (:invalidated-evidence no-op-invalidation))]
         :final-outcomes
         (mapv #(portable-outcome % reader-index (:version (current-record session reader-index)))
               (constraint-index/outcomes index4))
         :final-result (semantic-result session)})
      (finally
        (runtime/stop-clocks! session)))))

(defn- percentile-summary [samples path]
  (let [values (vec (sort (keep #(get-in % path) samples)))]
    (when (seq values)
      {:raw values
       :median (nth values (quot (count values) 2))
       :range [(first values) (peek values)]})))

(defn- summarize-scenario [{:keys [measurements]}]
  {:incremental (percentile-summary measurements [:incremental :elapsed-ns])
   :clean (percentile-summary measurements [:clean :elapsed-ns])
   :semantic-agreement (every? :semantic-agreement? measurements)
   :within-60-seconds
   (every? (fn [sample]
             (and (get-in sample [:incremental :within-limit?])
                  (get-in sample [:clean :within-limit?])))
           measurements)})

(defn- instrumentation-run []
  (let [session (new-fixture-session)]
    (try
      (runtime/import-instance! session (fixture-instance true))
      (let [samples
            (mapv
             (fn [ordinal]
               (let [instrumented-first? (even? ordinal)
                     run (fn [instrument? suffix]
                           (compact-sample
                            (incremental-sample!
                             session
                             (request session unrelated-index
                                      (pr-str (list 'define 'benchmark-unrelated
                                                    (keyword (str suffix "-" ordinal)))))
                             instrument?)))
                     first-sample (run instrumented-first? "first")
                     second-sample (run (not instrumented-first?) "second")]
                 {:ordinal ordinal
                  :phase (if (< ordinal 3) :warmup :measured)
                  :order (if instrumented-first?
                           :instrumented-first :uninstrumented-first)
                  :instrumented (if instrumented-first? first-sample second-sample)
                  :uninstrumented (if instrumented-first? second-sample first-sample)}))
             (range 8))
            measured (subvec samples 3)]
        {:warmups (subvec samples 0 3)
         :measurements measured
         :summary
         {:instrumented (percentile-summary measured [:instrumented :elapsed-ns])
          :uninstrumented (percentile-summary measured [:uninstrumented :elapsed-ns])}})
      (finally
        (runtime/stop-clocks! session)))))

(defn run
  "Run the prescribed cold start, edit sequence, alternating comparison, and
  instrumentation-overhead measurements. Returned data is portable EDN."
  []
  (let [started (str (Instant/now))
        cold (import-sample (fixture-instance true))
        edits (edit-sequence-run)
        scenarios (mapv scenario-run [:unrelated :implementation])
        instrumentation (instrumentation-run)
        stopped (str (Instant/now))]
    {:run/id :stack-r9-measured-2
     :specification/revision baseline/specification-revision
     :repository/revision
     (let [{:keys [exit out]} (shell/sh "git" "rev-parse" "HEAD")]
       (when (zero? exit) (str/trim out)))
     :source/manifest (baseline/source-manifest ".")
     :fixture fixture-id
     :implementation-premise-count (count (baseline/implementation-commits))
     :fixture-expectations baseline/fixture-expectations
     :edit/sequence baseline/edit-sequence
     :environment {:java-version (System/getProperty "java.version")
                   :java-vm (System/getProperty "java.vm.name")
                   :os-name (System/getProperty "os.name")
                   :os-version (System/getProperty "os.version")
                   :os-arch (System/getProperty "os.arch")}
     :budgets {:sample-limit-seconds 60 :warmups 3 :measurements 5}
     :instrumentation {:activation-events true
                       :temperature true
                       :synchronous-profiler-not-browser-latency true}
     :work-session {:started-at started :stopped-at stopped
                    :classification :agent-development-and-runtime-wait}
     :cold-start cold
     :edit-sequence edits
     :scenarios scenarios
     :scenario-summaries (into {} (map (juxt :scenario summarize-scenario)) scenarios)
     :instrumentation-overhead instrumentation}))

(defn- compact-timed-sample [sample]
  (-> (select-keys sample [:elapsed-ns :within-limit? :receipt :runtime-profile
                           :temperature :retained :work])
      (assoc :semantic-result
             (select-keys (:semantic-result sample)
                          [:status :occurrence-identities :diagnostics]))))

(defn- compact-pair [pair]
  (-> (select-keys pair [:ordinal :phase :order :semantic-agreement?])
      (assoc :incremental (compact-timed-sample (:incremental pair))
             :clean (compact-timed-sample (:clean pair)))))

(defn- compact-edit [edit]
  (cond-> (select-keys edit [:edit :status :commits :block-index :from-version
                             :to-version :constraint-evaluations
                             :invalidated-outcomes :invalidated-evidence
                             :result :ledger-unchanged?])
    (:sample edit) (assoc :sample (compact-timed-sample (:sample edit)))
    (:samples edit)
    (assoc :samples
           (mapv (fn [install]
                   {:edit (:edit install)
                    :index (:index install)
                    :sample (compact-timed-sample (:sample install))})
                 (:samples edit)))))

(defn compact-artifact
  "Remove repeated normalized scenes while retaining every raw timing, order,
  status, work counter, and instrumentation summary needed for reproduction."
  [run]
  (-> run
      (update :cold-start compact-timed-sample)
      (update-in [:edit-sequence :steps] #(mapv compact-edit %))
      (update-in [:edit-sequence :final-result]
                 #(select-keys % [:status :occurrence-identities :diagnostics]))
      (update :scenarios
              #(mapv (fn [scenario]
                       (-> scenario
                           (update :warmups (fn [xs] (mapv compact-pair xs)))
                           (update :measurements (fn [xs] (mapv compact-pair xs)))))
                     %))
      (update-in [:instrumentation-overhead :warmups]
                 #(mapv (fn [sample]
                          (-> (select-keys sample [:ordinal :phase :order])
                              (assoc :instrumented
                                     (compact-timed-sample (:instrumented sample))
                                     :uninstrumented
                                     (compact-timed-sample (:uninstrumented sample))))) %))
      (update-in [:instrumentation-overhead :measurements]
                 #(mapv (fn [sample]
                          (-> (select-keys sample [:ordinal :phase :order])
                              (assoc :instrumented
                                     (compact-timed-sample (:instrumented sample))
                                     :uninstrumented
                                     (compact-timed-sample (:uninstrumented sample))))) %))))
