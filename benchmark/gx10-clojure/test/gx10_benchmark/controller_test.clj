(ns gx10-benchmark.controller-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [graph.json :as json]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.workspace.model :as workspace]
            [gx10-benchmark.controller :as controller]
            [gx10-benchmark.journal :as journal]
            [gx10-benchmark.outcome-test :as fixtures]
            [gx10-benchmark.session :as session])
  (:import [java.nio.file Files]))

(defn temporary []
  (.toFile (Files/createTempDirectory "gx10-clojure-test-"
                                       (make-array java.nio.file.attribute.FileAttribute 0))))

(def sample-cell (controller/cell "test-model" "distinct" {:context 131072 :temperature 1.0}))

(defn effects [calls]
  (into {}
        (for [stage (remove #{:save-result} controller/expected-stages)]
          [stage (fn [_]
                   (swap! calls conj stage)
                   (merge {:ok? true :verified? true :approved? true}
                          (case stage
                            :execute {:execution fixtures/normal :workflow {}}
                            :evaluate {:evaluation fixtures/evaluation}
                            {})))])))

(deftest specification-then-session-refinement
  (let [candidate (session/prepare!)]
    (try
      (is (= controller/expected-stages (session/stages (session/binding-value candidate 'result))))
      (is (= 27487790695 (session/binding-value candidate 'reserve-for-128-gib)))
      (is (true? (session/binding-value candidate 'recovered-abort-can-continue)))
      (is (false? (session/binding-value candidate 'uncertain-cleanup-must-stop)))
      (is (= 1 (count (:extensions candidate))))
      (finally (session/close! candidate)))))

(deftest saved-workspace-replays-the-same-refined-program
  (let [document (workspace/validate (json/read-json (session/source "workspace.json")))
        pane (first (:panes document))
        candidate (editor/prepare (:executedSource pane) (:extensions pane))]
    (try
      (is (= controller/expected-stages (session/stages (session/binding-value candidate 'result))))
      (is (false? (session/binding-value candidate 'uncertain-cleanup-must-stop)))
      (finally (session/close! candidate)))))

(deftest recovered-abort-does-not-block-next-cell
  (let [parent (temporary) calls (atom [])
        ports (assoc (effects calls) :execute (fn [_] {:ok? true :execution fixtures/recovered}))
        results (controller/run-queue! [sample-cell sample-cell] (io/file parent "queue") ports)]
    (is (= 2 (count results)))
    (is (every? :correct? results))
    (is (= :finished (:stage (journal/read-record (io/file parent "queue/queue.edn")))))
    (is (= 2 (count (filter #{:load-model} @calls))))))

(deftest unknown-abort-stops-queue
  (let [parent (temporary) calls (atom [])
        broken (assoc fixtures/normal :events [{:type :assistant-end :stop :aborted :request 1} {:type :agent-end}])
        results (controller/run-queue!
                 [sample-cell sample-cell] (io/file parent "queue")
                 (assoc (effects calls) :execute (fn [_] {:ok? true :execution broken})))]
    (is (= 1 (count results)))
    (is (true? (:correct? (first results))))
    (is (= :runtime-error (:execution (first results))))
    (is (= 1 (count (filter #{:load-model} @calls))))))

(deftest no-loading-before-settlement
  (let [calls (atom []) ports (assoc (effects calls) :settle-memory (fn [_] {:ok? true :verified? false}))
        result (controller/run-cell! sample-cell (io/file (temporary) "cell") ports)]
    (is (not (:correct? result)))
    (is (true? (:cleanup-verified? result)))
    (is (not-any? #{:check-budget :load-model} @calls))))

(deftest result-precedes-cleanup-and-next-load
  (let [calls (atom []) ports (effects calls) parent (temporary)
        cleanup (fn [context]
                  (let [record (journal/read-record (io/file (:directory context) "result.edn"))]
                    (is (:durable? record))
                    (is (false? (:cleanup-verified? record))))
                  {:ok? true :verified? true})]
    (controller/run-queue! [sample-cell sample-cell] (io/file parent "queue")
                           (assoc ports :cleanup-after cleanup))))

(deftest cleanup-failure-prevents-next-model
  (let [calls (atom [])
        results (controller/run-queue!
                 [sample-cell sample-cell] (io/file (temporary) "queue")
                 (assoc (effects calls) :cleanup-after (fn [_] (throw (ex-info "owned process remains" {})))))]
    (is (= 1 (count results)))
    (is (false? (:cleanup-verified? (first results))))
    (is (= 1 (count (filter #{:load-model} @calls))))))

(deftest bounded-repeat-reloads-into-fresh-directories
  (let [paths (atom []) calls (atom [])
        repeated (assoc fixtures/normal :events [{:type :anti-repeat :action :corrected :kind :reasoning-repeat}
                                                {:type :anti-repeat :action :stopped :kind :reasoning-repeat}])
        results (controller/run-queue!
                 [sample-cell] (io/file (temporary) "queue")
                 (assoc (effects calls) :execute
                        (fn [context]
                          (swap! paths conj (:directory context))
                          {:ok? true :execution repeated})))]
    (is (= 2 (count @paths)))
    (is (= 2 (count (set @paths))))
    (is (= 1 (:reloads-used (first results))))
    (is (:reload-exhausted? (first results)))
    (is (false? (:timing-valid? (first results))))))

(deftest interruption-during-each-phase-is-durable
  (doseq [stage [:check-budget :load-model :execute :evaluate]]
    (let [root (io/file (temporary) "cell")
          ports (assoc (effects (atom [])) stage (fn [_] (throw (InterruptedException. "test interruption"))))
          result (controller/run-cell! sample-cell root ports)]
      (is (= :interrupted (:execution result)))
      (is (:cleanup-verified? result))
      (is (= result (journal/read-record (io/file root "result.edn"))))
      (is (= :complete (:stage (journal/read-record (io/file root "checkpoint.edn"))))))))

(deftest existing-output-is-never-overwritten
  (let [directory (temporary)]
    (is (thrown? java.nio.file.FileAlreadyExistsException
                 (controller/run-cell! sample-cell directory (effects (atom [])))))))

(deftest controller-lock-refuses-conflict
  (let [path (io/file (temporary) "host.lock")]
    (journal/with-host-lock! path
      #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"owns the host lock"
                             (journal/with-host-lock! path (fn [] :unexpected)))))))
