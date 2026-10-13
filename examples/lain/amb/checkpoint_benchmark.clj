(ns examples.lain.amb.checkpoint-benchmark
  "Paired single-thread restoration experiment, not a new production solver."
  (:require [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.builder :as builder]
            [examples.lain.amb.checkpoint-question :as question]
            [examples.lain.amb.checkpoint-search :as search]
            [propagators.experimental.amb.observation :as observation]
            [propagators.network-cache :as cache]
            [propagators.runner :as runner])
  (:import [java.lang.management ManagementFactory]))

(defn allocated-bytes []
  (let [bean (ManagementFactory/getThreadMXBean)]
    (if (and (instance? com.sun.management.ThreadMXBean bean)
             (.isThreadAllocatedMemoryEnabled ^com.sun.management.ThreadMXBean bean))
      (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean
                                (.getId (Thread/currentThread)))
      nil)))

(defn- reference-run [built]
  (let [metrics (atom {:activations 0})
        started (System/nanoTime)
        result (binding [cache/*cache* nil
                         runner/*advance-transform* (search/counter-transform metrics)]
                 (runner/run-network (:tasks built) (:network built)))]
    (case (:status result)
      :completed
      (let [network (:network result)
            answer (observation/cell-base network (get-in built [:cells :output]))
            repair (observation/cell-base network (get-in built [:cells :repairs]))
            records (map #(observation/cell-base network %) (get-in built [:cells :controls]))
            status (cond
                     (vector? answer) :solution
                     (= :unsatisfiable (:action repair)) :exhausted
                     :else (throw (ex-info "AMB reference remains unresolved" {})))
            solution (if (= :solution status) answer nil)]
        {:status status :solution solution
         :run-ms (/ (- (System/nanoTime) started) 1e6)
         :metrics (assoc @metrics
                         :repairs (reduce + (map #(quot (dec (:timestamp %)) 2) records))
                         :nogoods (count (observation/cell-base network (get-in built [:cells :knowledge]))))})
      :failed (throw (:error result))
      (throw (ex-info "Unknown AMB reference result" {:status (:status result)})))))

(defn sample [f]
  (let [before (allocated-bytes)
        result (f)
        after (allocated-bytes)
        bytes (if (and (some? before) (some? after)) (- after before) nil)]
    (assoc (select-keys result [:status :solution :run-ms :metrics]) :allocated-bytes bytes)))

(defn- median [values]
  (let [ordered (vec (sort values)) n (count ordered)]
    (if (odd? n)
      (nth ordered (quot n 2))
      (/ (+ (nth ordered (dec (quot n 2))) (nth ordered (quot n 2))) 2.0))))

(defn summarize [samples]
  (let [times (mapv :run-ms samples)
        allocation (keep :allocated-bytes samples)]
    {:median-ms (median times) :min-ms (apply min times) :max-ms (apply max times)
     :median-allocated-bytes (if (seq allocation) (median allocation) nil)
     :status (:status (first samples)) :solution (:solution (first samples))
     :metrics (:metrics (first samples))
     :samples samples}))

(defn- checked [n run]
  (let [expected (:solution (oracle/chronological n))
        expected-status (if (some? expected) :solution :exhausted)]
    (if (and (= expected (:solution run)) (= expected-status (:status run)))
      run
      (throw (ex-info "Benchmark differs from independent oracle"
                      {:domain n :expected expected :actual run})))))

(defn- measured [n label f]
  (let [result (checked n (sample f))]
    (binding [*out* *err*]
      (prn {:domain n :mode label :run-ms (:run-ms result)}))
    result))

(defn compare-domain [n samples]
  (let [started (System/nanoTime)
        common (question/build n)
        common-setup-ms (/ (- (System/nanoTime) started) 1e6)
        reference-started (System/nanoTime)
        reference (builder/build n builder/pythagorean?)
        reference-setup-ms (/ (- (System/nanoTime) reference-started) 1e6)
        run-checkpoint #(search/run common :checkpoint)
        run-withdrawal #(search/run common :withdrawal)
        run-reference #(reference-run reference)]
    ;; Warm all three paths; setup and warmups are outside measured samples.
    (doseq [run [run-checkpoint run-withdrawal run-reference]] (checked n (run)))
    (let [rows (reduce (fn [rows index]
                         (let [modes (if (even? index)
                                       [[:checkpoint run-checkpoint] [:withdrawal run-withdrawal]]
                                       [[:withdrawal run-withdrawal] [:checkpoint run-checkpoint]])]
                           (reduce (fn [rows [label run]]
                                     (update rows label conj (measured n label run)))
                                   rows modes)))
                       {:checkpoint [] :withdrawal []} (range samples))
          checkpoint (summarize (:checkpoint rows))
          withdrawal (summarize (:withdrawal rows))
          reference-rows (vec (repeatedly samples #(measured n :learning-amb-reference run-reference)))]
      (if (= (select-keys (:metrics checkpoint) [:decisions :leaves :max-depth])
             (select-keys (:metrics withdrawal) [:decisions :leaves :max-depth]))
        {:domain n :common-setup-ms common-setup-ms :reference-setup-ms reference-setup-ms
         :checkpoint checkpoint :withdrawal withdrawal
         :learning-amb-reference (summarize reference-rows)
         :withdrawal-over-checkpoint (/ (:median-ms withdrawal) (:median-ms checkpoint))}
        (throw (ex-info "Restoration modes took different search paths" {:domain n}))))))

(defn -main [& arguments]
  (let [samples (if (seq arguments) (Long/parseLong (first arguments)) 5)]
    (if (pos? samples)
      (prn {:experiment :quiescent-checkpoint-vs-ttms-withdrawal
            :date "2026-10-13" :samples-per-mode samples :warmups-per-domain-mode 1
            :cache :disabled :learning-in-controlled-pair false
            :strategy :depth-first :threading :single
            :java-version (System/getProperty "java.version")
            :cases (mapv #(compare-domain % samples) [4 5 8])})
      (throw (ex-info "Positive sample count required" {:samples samples})))))
