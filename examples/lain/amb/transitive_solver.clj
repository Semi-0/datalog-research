(ns examples.lain.amb.transitive-solver
  "One Lain graph-knowledge question; three unchanged swappable solver policies."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [examples.lain.amb.experiment :as inspection]
            [examples.lain.amb.knowledge :as knowledge]
            [examples.lain.amb.solver-benchmark :as benchmark]
            [examples.lain.amb.solver-lain :as lain]
            [examples.lain.amb.solver-operator :as operator]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as session]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.network-cache :as cache]))

(def source (slurp (io/resource "examples/lain/amb/transitive_solver.lain")))
(def modes [:repair :checkpoint :concurrent])
(def scenarios [:graph-chain :graph-cycle :dataflow-fork-join])

(defn compile-query [reach domain]
  (let [solver-cell (ids/new-node-id)
        program (str/replace source "DOMAIN" (str "(list " (str/join " " domain) ")"))
        state (-> (session/empty-state)
                  (update :program/net nb/ensure-cell solver-cell)
                  (extension/install-session-extension ttms/session-extension {})
                  (extension/install-session-extension (operator/session-extension {:workers 4}) {})
                  (extension/install-session-extension (knowledge/query-extension reach) {})
                  (extension/install-session-extension
                   (extension/extension-bundle
                    {:id ::selection :bindings [['solver (env/cell-binding solver-cell)]] :effects []}) {}))]
    (assoc (compiler/compile-source program (:program/env state)
                                   {:net (:program/net state) :environment-props (:program/props state)})
           :solver-cell solver-cell :source program)))

(defn prepare [scenario]
  (let [inferred (binding [cache/*cache* nil] (knowledge/infer (:domain scenario) (:edges scenario)))
        expected (knowledge/closure-oracle (:domain scenario) (:edges scenario))]
    (if (= expected (:reach inferred))
      (let [started (System/nanoTime)
            compiled (compile-query (:reach inferred) (:domain scenario))
            at (System/nanoTime) state (binding [cache/*cache* nil] (lain/prepare compiled))]
        {:scenario (:id scenario) :domain (:domain scenario) :edges (:edges scenario)
         :reach (:reach inferred) :compiled compiled :state state
         :expected (knowledge/answers-oracle (:domain scenario) (:edges scenario))
         :inference {:compile-ms (:compile-ms inferred) :run-ms (:ms inferred) :steps (:steps inferred)
                     :topology (inspection/topology (:network inferred))}
         :compile-ms (/ (- at started) 1e6) :prepare-ms (/ (- (System/nanoTime) at) 1e6)
         :outer-topology (inspection/topology (:network state))
         :subject (if (:subject-network scenario)
                    {:topology (inspection/topology (:subject-network scenario))
                     :names (:names scenario) :values (:values scenario)
                     :source-cells (mapv #(str (ids/unwrap-node-id %)) (:subject-cells scenario))}
                    nil)})
      (throw (ex-info "Knowledge propagation disagrees with independent BFS"
                      {:scenario (:id scenario) :expected expected :actual (:reach inferred)})))))

(defn checked [fixture choice row]
  (let [n (count (:domain fixture))]
    (if (and (= :completed (:status row)) (= (:expected fixture) (:answers row))
           (= 1 (:solver-calls row))
           (or (= :repair choice)
               (= (* n n n) (get-in row [:metrics :leaves]))))
      row
      (throw (ex-info "Transitive solver differs from exhaustive BFS-based oracle"
                      {:scenario (:scenario fixture) :strategy choice :expected (:expected fixture) :result row})))))

(defn sample [fixture choice pool]
  (checked fixture choice (benchmark/sample fixture choice pool)))

(defn compare-scenario [id pool repeats]
  (let [fixture (prepare (knowledge/scenario id))]
    (doseq [choice modes] (sample fixture choice pool))
    (let [rows (reduce
                (fn [rows index]
                  (reduce (fn [rows choice]
                            (let [row (sample fixture choice pool)]
                              (binding [*out* *err*]
                                (prn {:scenario id :strategy choice :answers (count (:answers row))
                                      :ms (:run-ms row)}))
                              (update rows choice (fnil conj []) row))) rows
                          (if (even? index) modes (reverse modes))))
                {} (range repeats))]
      (merge (select-keys fixture [:scenario :domain :inference :compile-ms :prepare-ms :outer-topology :subject])
             {:edges (vec (sort (:edges fixture))) :reach (vec (sort (:reach fixture)))
              :expected (:expected fixture) :modes (into {} (map (fn [[mode rows]] [mode (benchmark/summary rows)])) rows)}))))

(defn -main [& arguments]
  (let [repeats (if (seq arguments) (Long/parseLong (first arguments)) 3)
        selected (if (next arguments) (mapv keyword (rest arguments)) scenarios)]
    (if (and (pos? repeats) (seq selected))
      (let [pool (driver/pool 4)]
        (try
          (prn {:experiment :transitive-knowledge-swappable-solvers :date "2026-10-13"
                :samples repeats :warmups 1 :workers 4 :cache :disabled
                :inference :positive-network-fixed-point :knowledge :fixed-during-query
                :java (System/getProperty "java.version")
                :processors (.availableProcessors (Runtime/getRuntime))
                :cases (mapv #(compare-scenario % pool repeats) selected)})
          (finally (driver/close! pool))))
      (throw (ex-info "Positive repeats and at least one scenario required" {})))))
