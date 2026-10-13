(ns examples.lain.amb.learning-checkpoint-benchmark
  "Matched solver swap plus early-refutation ablation. Oracle checks are untimed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [examples.lain.amb.checkpoint-benchmark :as measurement]
            [examples.lain.amb.concurrent-learning-checkpoint :as parallel-hybrid]
            [examples.lain.amb.knowledge :as knowledge]
            [examples.lain.amb.learning-checkpoint :as hybrid]
            [examples.lain.amb.parallel-benchmark :as profiling]
            [examples.lain.amb.solver-lain :as lain]
            [examples.lain.amb.solver-operator :as operator]
            [examples.lain.amb.solver-strategy :as strategy]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as session]
            [propagators.core :as core]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.publication :as publication]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.network-cache :as cache]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def source (slurp (io/resource "examples/lain/amb/learning_checkpoint.lain")))
(def modes [:repair :checkpoint :concurrent :early-checkpoint :learning-checkpoint])
(def scenarios [:conjunction :prefix :suffix])
(def policies
  (merge (select-keys strategy/strategies [:repair :checkpoint :concurrent])
         {:early-checkpoint hybrid/early-solver :learning-checkpoint hybrid/learning-solver
          :partitioned-learning parallel-hybrid/sequential-solver
          :concurrent-learning parallel-hybrid/concurrent-solver}))

(defn body [shape]
  (case shape
    :conjunction "(and (reachable? facts a b) (reachable? facts b c))"
    :prefix "(reachable? facts a b)"
    :suffix "(reachable? facts b c)"
    (throw (ex-info "Unknown required predicate shape" {:shape shape}))))

(defn accepted? [shape reach [a b c]]
  (case shape
    :conjunction (and (contains? reach [a b]) (contains? reach [b c]))
    :prefix (contains? reach [a b])
    :suffix (contains? reach [b c])
    (throw (ex-info "Unknown oracle shape" {:shape shape}))))

(defn answers [shape domain edges]
  (let [reach (knowledge/closure-oracle domain edges)]
    (vec (sort (for [a domain b domain c domain :when (accepted? shape reach [a b c])]
                [a b c])))))

(defn compile-query [program reach]
  (let [solver-cell (ids/new-node-id)
        state (-> (session/empty-state)
                  (update :program/net nb/ensure-cell solver-cell)
                  (extension/install-session-extension ttms/session-extension {})
                  (extension/install-session-extension (operator/session-extension {:workers 4}) {})
                  (extension/install-session-extension hybrid/session-extension {})
                  (extension/install-session-extension parallel-hybrid/session-extension {})
                  (extension/install-session-extension (knowledge/query-extension reach) {})
                  (extension/install-session-extension
                   (extension/extension-bundle
                    {:id ::selection :bindings [['solver (env/cell-binding solver-cell)]] :effects []}) {}))]
    (assoc (compiler/compile-source program (:program/env state)
                                   {:net (:program/net state) :environment-props (:program/props state)})
           :solver-cell solver-cell :source program)))

(defn program [predicate domain]
  (-> source
      (str/replace "CONSTRAINT" (str "(network (a b c) " predicate ")"))
      (str/replace "DOMAIN" (str "(list " (str/join " " domain) ")"))))

(defn prepare-fixture [scenario shape]
  (let [inferred (binding [cache/*cache* nil] (knowledge/infer (:domain scenario) (:edges scenario)))
        _ (if (= (:reach inferred) (knowledge/closure-oracle (:domain scenario) (:edges scenario)))
            nil (throw (ex-info "Inference disagrees with BFS" {})))
        at (System/nanoTime)
        compiled (compile-query (program (body shape) (:domain scenario)) (:reach inferred))
        compiled-at (System/nanoTime) state (binding [cache/*cache* nil] (lain/prepare compiled))]
    {:shape shape :domain (:domain scenario) :edges (:edges scenario) :reach (:reach inferred)
     :compiled compiled :state state :expected (answers shape (:domain scenario) (:edges scenario))
     :setup {:inference-compile-ms (:compile-ms inferred) :inference-ms (:ms inferred)
             :query-compile-ms (/ (- compiled-at at) 1e6)
             :prepare-ms (/ (- (System/nanoTime) compiled-at) 1e6)}}))

(defn prepare [shape]
  (prepare-fixture (knowledge/scenario (if (= :conjunction shape) :dataflow-fork-join :graph-chain)) shape))

(defn question-of [network]
  (let [matches (filter #(and (prop/prop? (val %))
                             (= ::operator/prepare (prop/prop-name (val %)))) (:env network))]
    (if (= 1 (count matches))
      (let [id (ffirst matches) query (first (get-in network [:graph id :outputs]))]
        (:question (observation/cell-base network query)))
      (throw (ex-info "Expected one prepared question" {:matches (count matches)})))))

(defn select-and-run [compiled network solver]
  (if (satisfies? strategy/ConstraintSolver solver)
    (let [emitted (publication/snapshot-message network (:solver-cell compiled) solver)
          [fresh updated] (core/eval-cell (:solver-cell compiled) (update emitted :value collection/content) network)]
      (lain/run-state compiled {:network updated :tasks (tq/merge-queues (tq/into-queue (:props compiled)) fresh)}))
    (throw (ex-info "Selection must implement ConstraintSolver" {}))))

(defn sound-nogoods? [question known valid-answers]
  (let [positions (zipmap (:sources question) (range))]
    (every? (fn [nogood]
              (every? (fn [tuple]
                        (not (every? (fn [[source index]]
                                       (= (nth (:domain question) index) (nth tuple (get positions source))))
                                     (:literals nogood)))) valid-answers)) known)))

(defn proof-examples [question known]
  (let [names (zipmap (:sources question) [:a :b :c])]
    (->> known
         (map (fn [nogood]
                {:literals (vec (sort (map (fn [[source index]]
                                             [(get names source) (nth (:domain question) index)])
                                           (:literals nogood))))
                 :context-size (count (:context nogood))}))
         (sort-by pr-str) (take 3) vec)))

(defn search-statistics [search]
  (let [keys [:decisions :leaves :evaluations :pruned-jobs :early-failures
              :propagated-leaves :covered-assignments :nogood-count]
        fronts (if (seq (:branches search)) (map :frontier (:branches search)) [(:frontier search)])]
    (reduce (fn [result frontier]
              (merge-with + result (select-keys frontier keys))) {} fronts)))

(defn retained-nogoods [search]
  (cond
    (contains? search :nogoods) (:nogoods search)
    (contains? search :logical-nogoods) (:logical-nogoods search)
    (seq (:branches search)) (into #{} (mapcat :nogoods) (:branches search))
    :else nil))

(defn sample [fixture mode pool]
  (let [solver (get policies mode)
        events (atom [])
        result (profiling/profile
                #(binding [strategy/*executor* pool operator/*observe-solve* (fn [event] (swap! events conj event))]
                   (select-and-run (:compiled fixture) (get-in fixture [:state :network]) solver)))
        event (peek @events) search (:result event) parent (:profile result)
        workers (mapv :profile (:branches search))
        ;; Sequential worker allocations are already included in the parent.
        allocated (map :allocated-bytes (cons parent (remove #(= (:thread parent) (:thread %)) workers)))
        known (retained-nogoods search) statistics (search-statistics search)
        n (count (:domain fixture))
        _ (if (and (= :completed (:status result)) (= (:expected fixture) (:answers result))
                   (= 1 (count @events)) (sound-nogoods? (:question event) known (:expected fixture))
                   (or (not (contains? #{:early-checkpoint :learning-checkpoint
                                        :partitioned-learning :concurrent-learning} mode))
                       (= (* n n n) (:covered-assignments statistics))))
            nil (throw (ex-info "Learning checkpoint differs from complete oracle"
                               {:mode mode :answers (:answers result) :expected (:expected fixture)})))]
    {:status (:status result) :answers (:answers result) :run-ms (:run-ms parent)
     :allocated-bytes (if (every? some? allocated) (reduce + allocated) nil)
     :metrics {:activations (reduce + (get-in parent [:metrics :activations])
                                   (map #(get-in % [:metrics :activations]) workers))
               :peak-env (apply max (get-in parent [:metrics :peak-env])
                                (map #(get-in % [:metrics :peak-env]) workers))
               :execution-threads (if (seq workers) (count (set (map :thread workers))) 1)}
     :search statistics
     :partitions (mapv (fn [values branch]
                         {:range values :answers (count (:answers branch))
                          :search (search-statistics branch) :nogoods (count (:nogoods branch))
                          :thread (get-in branch [:profile :thread])})
                       (:ranges search) (:branches search))
     :nogood-widths (frequencies (map (comp count :literals) known))
     :proof-examples (proof-examples (:question event) known)
     :solver-calls (count @events) :sound-nogoods? true}))

(defn compare-case [shape pool repeats]
  (let [fixture (prepare shape)]
    (doseq [mode modes] (sample fixture mode pool))
    (let [rows (reduce
                (fn [rows index]
                  (reduce (fn [rows mode]
                            (let [row (sample fixture mode pool)]
                              (binding [*out* *err*]
                                (prn {:shape shape :mode mode :ms (:run-ms row) :search (:search row)}))
                              (update rows mode (fnil conj []) row))) rows
                          (if (even? index) modes (reverse modes))))
                {} (range repeats))]
      {:shape shape :domain (:domain fixture) :edges (vec (sort (:edges fixture)))
       :source (get-in fixture [:compiled :source]) :setup (:setup fixture)
       :expected (:expected fixture)
       :modes (into {} (map (fn [[mode rows]] [mode (assoc (measurement/summarize rows)
                                                         :answers (:answers (first rows)))])) rows)})))

(defn -main [& arguments]
  (let [repeats (if (seq arguments) (Long/parseLong (first arguments)) 3)
        selected (if (next arguments) (mapv keyword (rest arguments)) scenarios)]
    (if (and (pos? repeats) (seq selected))
      (let [pool (driver/pool 4)]
        (try
          (prn {:experiment :checkpoint-nogood-learning :date "2026-10-13" :samples repeats
                :warmups 1 :workers 4 :cache :disabled :proof :required-predicate-support
                :java (System/getProperty "java.version")
                :cases (mapv #(compare-case % pool repeats) selected)})
          (finally (driver/close! pool))))
      (throw (ex-info "Positive repeats and at least one shape required" {})))))
