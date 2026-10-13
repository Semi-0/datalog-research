(ns examples.lain.amb.strategy-benchmark
  "Six contemporaneous, oracle-checked modes. Setup and allocation are explicit."
  (:require [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.builder :as builder]
            [examples.lain.amb.checkpoint-benchmark :as measurements]
            [examples.lain.amb.checkpoint-question :as question]
            [examples.lain.amb.checkpoint-search :as checkpoint]
            [examples.lain.amb.domain-refinement :as domain]
            [examples.lain.amb.incremental-learning :as incremental]
            [examples.lain.amb.network-search :as search]
            [propagators.experimental.amb.learning :as learning]
            [propagators.experimental.amb.observation :as observation]
            [propagators.network-cache :as cache]
            [propagators.runner :as runner]))

(def ^:dynamic *predicate-checks* nil)

(defn counted-predicate [a b c]
  (if *predicate-checks* (swap! *predicate-checks* inc) nil)
  (builder/pythagorean? a b c))

(defn run-amb [built]
  (let [result (runner/run-network (:tasks built) (:network built))]
    (case (:status result)
      :completed
      (let [network (:network result)
            solution (observation/cell-base network (get-in built [:cells :output]))
            repair (observation/cell-base network (get-in built [:cells :repairs]))
            status (cond (vector? solution) :solution
                         (= :unsatisfiable (:action repair)) :exhausted
                         :else (throw (ex-info "AMB remains unresolved" {})))
            knowledge (observation/cell-base network (get-in built [:cells :knowledge]))]
        {:status status :solution (if (= :solution status) solution nil)
         :metrics {:nogoods (count knowledge)}})
      :failed (throw (:error result))
      (throw (ex-info "Unknown AMB runtime status" result)))))

(defn run-network-search [built]
  (let [result (runner/run-network (:tasks built) (:network built))]
    (case (:status result)
      :completed
      (let [answer (search/answer built (:network result))]
        (assoc (select-keys answer [:status :solution])
               :metrics (select-keys (:frontier answer) [:decisions :leaves :peak-frontier])))
      :failed (throw (:error result))
      (throw (ex-info "Unknown network search runtime status" result)))))

(defn sample [f]
  (let [metrics (atom {:activations 0}) checks (atom 0) resolutions (atom 0)
        original learning/resolve-choice
        before (measurements/allocated-bytes) started (System/nanoTime)
        result (binding [cache/*cache* nil *predicate-checks* checks
                         runner/*advance-transform* (checkpoint/counter-transform metrics)]
                 ;; Diagnostic only, in an isolated benchmark JVM; no live server.
                 (with-redefs [learning/resolve-choice
                               (fn [knowledge record]
                                 (swap! resolutions inc)
                                 (original knowledge record))]
                   (f)))
        elapsed (/ (- (System/nanoTime) started) 1e6)
        after (measurements/allocated-bytes)]
    {:status (:status result) :solution (:solution result) :run-ms elapsed
     :allocated-bytes (if (and before after) (- after before) nil)
     :metrics (merge @metrics (:metrics result)
                     {:predicate-checks @checks :resolution-calls @resolutions})}))

(defn- timed-build [f]
  (let [started (System/nanoTime) built (f)]
    {:built built :setup-ms (/ (- (System/nanoTime) started) 1e6)}))

(defn scenarios [n]
  (let [common (timed-build #(question/build n counted-predicate))
        original (timed-build #(builder/build n counted-predicate))
        delta (timed-build #(incremental/build n counted-predicate))
        network (timed-build #(search/build (:built common)))
        domains (timed-build #(domain/build n counted-predicate))
        domain-network (timed-build #(search/build (:built domains)))]
    [{:mode :checkpoint-dfs :setup-ms (:setup-ms common)
      :run #(checkpoint/run (:built common) :checkpoint)}
     {:mode :withdrawal-dfs :setup-ms (:setup-ms common)
      :run #(checkpoint/run (:built common) :withdrawal)}
     {:mode :learning-amb :setup-ms (:setup-ms original) :run #(run-amb (:built original))}
     {:mode :incremental-learning :setup-ms (:setup-ms delta) :run #(run-amb (:built delta))}
     {:mode :network-checkpoint :setup-ms (+ (:setup-ms common) (:setup-ms network))
      :run #(run-network-search (:built network))}
     {:mode :domain-network :setup-ms (+ (:setup-ms domains) (:setup-ms domain-network))
      :run #(run-network-search (:built domain-network))}]))

(defn- checked [n result]
  (let [expected (:solution (oracle/chronological n))
        status (if (some? expected) :solution :exhausted)]
    (if (and (= expected (:solution result)) (= status (:status result))) result
        (throw (ex-info "Strategy differs from oracle" {:domain n :result result})))))

(defn compare-domain [n repeats]
  (let [strategies (scenarios n)]
    (doseq [{:keys [run]} strategies] (checked n (sample run)))
    (let [samples (reduce (fn [rows index]
                           (let [ordered (if (even? index) strategies (reverse strategies))]
                             (reduce (fn [rows {:keys [mode run]}]
                                       (let [result (checked n (sample run))]
                                         (binding [*out* *err*] (prn {:domain n :mode mode :ms (:run-ms result)}))
                                         (update rows mode (fnil conj []) result))) rows ordered)))
                         {} (range repeats))]
      {:domain n :modes (into {} (map (fn [{:keys [mode setup-ms]}]
                                      [mode (assoc (measurements/summarize (get samples mode))
                                                   :setup-ms setup-ms)])) strategies)})))

(defn -main [& args]
  (let [repeats (if (seq args) (Long/parseLong (first args)) 5)]
    (if (pos? repeats)
      (prn {:experiment :three-network-strategies :date "2026-10-13"
            :samples-per-mode repeats :warmups-per-mode 1 :cache :disabled
            :threading :single :java-version (System/getProperty "java.version")
            :cases (mapv #(compare-domain % repeats) [4 5 8])})
      (throw (ex-info "Positive sample count required" {:samples repeats})))))
