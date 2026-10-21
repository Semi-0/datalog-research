(ns graph.lain-editor.experiment-baseline
  "Revision-pinned controlled replay for the constraint-first stack experiment."
  (:require [clojure.java.io :as io]
            [graph.lain-editor.development-session :as development-session]
            [graph.lain-editor.constraint-checkpoint :as checkpoint]
            [propagators.compiler-2.runtime :as runtime])
  (:import [java.security MessageDigest]
           [java.util UUID]))

(def specification-revision :stack-spec-2026-10-20-r9)
(def specification-revision-name (name specification-revision))
(def client-id "stack-baseline-r9")

(def source-paths
  ["examples/lain/top_down/stack-design.lain"
   "examples/lain/top_down/stack/structure.lain"
   "examples/lain/top_down/stack/constraints.lain"
   "examples/lain/top_down/stack/traversal-state.lain"
   "examples/lain/top_down/stack/hierarchy.lain"
   "examples/lain/top_down/stack/primitive-bindings-pending.lain"
   "examples/lain/top_down/stack/primitive-bindings.lain"
   "examples/lain/top_down/stack/traversal-implementation-pending.lain"
   "examples/lain/top_down/stack/traversal-implementation.lain"
   "examples/lain/top_down/stack/fixtures.lain"
   "examples/lain/top_down/stack/example.lain"])

(def fixture-expectations
  {:distinct-calls :satisfied
   :sibling-sharing :satisfied
   :reference-cycle :satisfied
   :deep-tail-expansion :satisfied
   :partial-child-tail :pending
   :conflicting-child-facts :contradictory
   :ambiguous-roots :violated
   :withdrawn-source :inactive
   :late-refinement :satisfied})

(def edit-sequence
  [:initial-pending :install-reader :unrelated-edit :replace-reader
   :withdraw-reader :restore-reader :failed-commit :no-op-edit])

(def domain-paths
  ["examples/lain/top_down/stack/structure.lain"
   "examples/lain/top_down/stack/constraints.lain"
   "examples/lain/top_down/stack/traversal-state.lain"
   "examples/lain/top_down/stack/hierarchy.lain"
   "examples/lain/top_down/stack/fixtures.lain"
   "examples/lain/top_down/stack/example.lain"])

(def pending-implementation-paths
  ["examples/lain/top_down/stack/primitive-bindings-pending.lain"
   "examples/lain/top_down/stack/traversal-implementation-pending.lain"])

(def implementation-paths
  ["examples/lain/top_down/stack/primitive-bindings.lain"
   "examples/lain/top_down/stack/traversal-implementation.lain"])

(defn read-forms [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (loop [forms []]
      (let [form (read {:eof ::eof} reader)]
        (if (= ::eof form) forms (recur (conj forms form)))))))

(defn pending-implementation-forms []
  (vec (mapcat read-forms pending-implementation-paths)))

(defn implementation-forms []
  (vec (mapcat read-forms implementation-paths)))

(defn specification-forms []
  (let [entry-forms (read-forms "examples/lain/top_down/stack-design.lain")
        ;; The flattened versioned ledger replaces the local import alias and
        ;; six import calls with their exact source forms. One form per block is
        ;; required by the versioned commit contract.
        entry-body (drop 7 entry-forms)]
    (vec (concat (mapcat read-forms domain-paths) entry-body))))

(defn- commit-id [serial]
  (format "10000000-0000-0000-0000-%012d" (inc serial)))

(defn initial-commits []
  (mapv (fn [index form]
          {:commit-id (commit-id index) :client-id client-id
           :index index :expected-version nil :text (pr-str form)})
        (range)
        (concat (pending-implementation-forms) (specification-forms))))

(defn implementation-commits
  "Replacement commits for the stable implementation slots."
  []
  (mapv (fn [index form]
          {:commit-id (format "20000000-0000-0000-0000-%012d" (inc index))
           :client-id client-id :index index :expected-version 0
           :text (pr-str form)})
        (range) (implementation-forms)))

(defn stack-query-index []
  (count (initial-commits)))

(defn stack-query-commit []
  {:commit-id "30000000-0000-0000-0000-000000000001"
   :client-id client-id :index (stack-query-index)
   :expected-version nil
   :text (str "(define frozen (observe-semantic-captured (example-pipeline 3) :both)) "
              "(compound->stack frozen (root-compound-node frozen))")})

(defn refined-instance []
  {:schema "compiler-2/versioned-instance"
   :schema-version 1
   :clients [{:client-id client-id :mode :versioned-premise}]
   :commits (vec (concat (initial-commits)
                         (implementation-commits)
                         [(stack-query-commit)]))})

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bytes)))

(defn sha-256 [bytes]
  (hex (.digest (MessageDigest/getInstance "SHA-256") bytes)))

(defn source-manifest
  ([root] (source-manifest root source-paths))
  ([root paths]
   (mapv (fn [path]
           (let [file (io/file root path)]
             (when-not (.isFile file)
               (throw (ex-info "Baseline source is missing" {:path path})))
             {:path path :sha256 (sha-256 (java.nio.file.Files/readAllBytes (.toPath file)))
              :bytes (.length file)}))
         paths)))

(defn initial-outcomes []
  (conj
   (mapv (fn [id]
           {:constraint/id id :specification/revision specification-revision
            :status :pending :required? true
            :reason :primitive-obligations-unresolved})
         (remove #{:withdrawn-source} (keys fixture-expectations)))
   {:constraint/id :withdrawn-source
    :specification/revision specification-revision
    :status :inactive :required? false :reason :fixture-not-activated}))

(defn controlled-input [root repository-revision]
  {:specification/revision specification-revision
   :repository/revision repository-revision
   :source/manifest (source-manifest root)
   :fixture/expectations fixture-expectations
   :edit/sequence edit-sequence
   :runtime/instance {:schema "compiler-2/versioned-instance"
                      :schema-version 1
                      :clients [{:client-id client-id :mode :versioned-premise}]
                      :commits (initial-commits)}})

(defn establish!
  "Build an isolated baseline from the pinned controlled input. Pending outcomes
  are intentional and remain in the required denominator."
  [root repository-revision]
  (let [input (controlled-input root repository-revision)
        construction-started (System/nanoTime)
        session (development-session/new-session)
        construction-ns (- (System/nanoTime) construction-started)
        started (System/nanoTime)
        replay (runtime/import-instance! session (:runtime/instance input))
        replay-ns (- (System/nanoTime) started)
        captured (checkpoint/checkpoint
                  {:id (keyword (str "baseline-" (UUID/randomUUID)))
                   :specification-revision specification-revision
                   :state @session
                   :outcomes (initial-outcomes)
                   :membership-complete? true
                   :fixture-expectations fixture-expectations
                   :diagnostics [{:kind :baseline/pending
                                  :message "Primitive obligations are intentionally unresolved"}]
                   :evidence [{:kind :controlled-input
                               :repository-revision repository-revision
                               :source-manifest (:source/manifest input)}]
                   :timing {:initial-network-construction-ns construction-ns
                            :commit-admission-compilation-and-propagation-ns replay-ns
                            :separation-status :runtime-boundary-not-yet-instrumented}})]
    {:input input
     :replay (select-keys replay [:status :commit-count])
     :checkpoint (checkpoint/portable-summary captured)
     :instance (runtime/export-instance @session)}))
