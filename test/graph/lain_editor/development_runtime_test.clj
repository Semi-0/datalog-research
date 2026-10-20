(ns graph.lain-editor.development-runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [graph.lain-editor.development-protocol :as protocol]
            [graph.lain-editor.development-runtime :as development]
            [graph.lain-editor.experiment-baseline :as baseline]))

(def pane "development-pane")
(def client "development-client")

(defn model []
  (protocol/portable-model baseline/specification-revision-name client
                           (protocol/empty-instance client)))

(deftest start-commit-replay-failure-and-close-are-atomic
  (let [registry (atom {})
        started (development/start! registry {:pane pane :model (model)})
        generation (:generation started)
        request {:pane pane :generation generation
                 :commitId "20000000-0000-0000-0000-000000000001"
                 :clientId client :index 0 :expectedVersion nil :text "41"}
        committed (development/commit! registry request)
        committed-generation (:generation committed)
        instance-before (:instance committed)]
    (is (not= generation committed-generation))
    (is (= 1 (count (get-in committed [:instance :commits]))))
    (is (some #(= 41 (:value %)) (get-in committed [:view :blocks])))
    (let [replayed (development/commit! registry (assoc request :generation committed-generation))]
      (is (true? (get-in replayed [:receipt :replayed?])))
      (is (= committed-generation (:generation replayed))))
    (testing "failed and stale commits do not replace the active generation or ledger"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"compilation failed"
                            (development/commit!
                             registry {:pane pane :generation committed-generation
                                       :commitId "20000000-0000-0000-0000-000000000002"
                                       :clientId client :index 0 :expectedVersion 0 :text "("})))
      (is (= committed-generation (:generation (development/require-entry registry pane))))
      (is (= instance-before
             (:instance (development/export registry {:pane pane :generation committed-generation}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed"
                            (development/commit! registry (assoc request :generation generation)))))
    (is (= {:closed pane} (development/close! registry {:pane pane :generation committed-generation})))
    (is (empty? @registry))))

(deftest checkpoint-is-a-read-only-projection-of-maintained-membership
  (let [registry (atom {})
        outcome {:constraint/id :shape
                 :specification/revision baseline/specification-revision
                 :status :pending :required? true :reason :missing-implementation}
        started (development/start! registry {:pane pane :model (model)
                                               :outcomes [outcome]
                                               :membershipComplete true})
        before @(:session (development/require-entry registry pane))
        result (development/checkpoint! registry {:pane pane :generation (:generation started)
                                                   :fixtureExpectations {:shape :pending}})
        after @(:session (development/require-entry registry pane))]
    (is (= before after))
    (is (= 1 (get-in result [:checkpoint :constraint/summary :required-count])))
    (is (= 1 (get-in result [:checkpoint :fixture/summary
                             :fixture-expectation-agreement])))))

(deftest commit-invalidates-superseded-support-and-applies-explicit-deltas
  (let [registry (atom {})
        v1 {:constraint/id :shape
            :specification/revision baseline/specification-revision
            :status :satisfied :required? true :dependencies []}
        started (development/start! registry {:pane pane :model (model)
                                               :outcomes [v1]
                                               :membershipComplete false})
        first-commit (development/commit!
                      registry {:pane pane :generation (:generation started)
                                :commitId "30000000-0000-0000-0000-000000000001"
                                :clientId client :index 0 :expectedVersion nil :text "41"
                                :outcomeUpdates
                                [{:op "upsert"
                                  :outcome (assoc v1 :dependencies [:premise/placeholder])}]})
        premise-id (get-in first-commit [:receipt :premise-id])
        entry (development/require-entry registry pane)
        _ (swap! registry assoc-in [pane :constraint-index :outcomes :shape :dependencies]
                 [premise-id])
        second-commit (development/commit!
                       registry {:pane pane :generation (:generation entry)
                                 :commitId "30000000-0000-0000-0000-000000000002"
                                 :clientId client :index 0 :expectedVersion 0 :text "42"
                                 :membershipComplete true})
        checkpoint (development/checkpoint!
                    registry {:pane pane :generation (:generation second-commit)})]
    (is (= [:shape] (get-in second-commit [:profile :invalidated-outcomes])))
    (is (= [:shape] (get-in second-commit [:profile :changed-outcomes])))
    (is (number? (get-in second-commit [:profile :commit-total-ns])))
    (is (= :pending
           (get-in checkpoint [:checkpoint :constraint/outcomes 0 :status])))
    (is (true? (get-in checkpoint [:checkpoint :constraint/summary
                                    :membership-complete?])))))

(deftest controlled-baseline-is-revision-pinned-and-replayable
  (let [root (System/getProperty "user.dir")
        input (baseline/controlled-input root "test-revision")
        run (baseline/establish! root "test-revision")]
    (is (= baseline/source-paths (mapv :path (:source/manifest input))))
    (is (every? #(re-matches #"[0-9a-f]{64}" (:sha256 %)) (:source/manifest input)))
    (is (= {:status :imported :commit-count (count (baseline/initial-commits))}
           (:replay run)))
    (is (= 8 (get-in run [:checkpoint :constraint/summary :required-count])))
    (is (= 8 (get-in run [:checkpoint :constraint/summary :pending])))
    (is (true? (get-in run [:checkpoint :constraint/summary :membership-complete?])))
    (is (not (contains? (:checkpoint run) :network/state)))))
