(ns examples.lain.amb.application-bench
  "Current compiler application benchmark. Each sample compiles a fresh network,
  runs it to quiescence, then replays its root propagators. Times exclude startup.
  Run: clojure -M -m examples.lain.amb.application-bench ordinary|registered|native [warmups samples]
  Paired control: paired [warmups samples] /path/to/baseline/propagators/gur/flat.clj"
  (:require [clojure.java.io :as io]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.cells.cell :as cell]
            [propagators.experimental.amb.observation :as observation]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(def ordinary-cases
  [{:case :primitive :expected 3 :source "(+ 1 2)"}
   {:case :closure :expected 5 :source "((network (x) (+ x 1)) 4)"}
   {:case :captured-closure :expected 9
    :source "(((network (x) (network (y) (+ x y))) 4) 5)"}
   {:case :recursive :expected 0
    :source "(let [] (define down (network (n) ((if (< n 1) (network () n) (network () (down (- n 1))))))) (down 4))"}])

(defn- topology [network]
  {:cells (count (filter cell/cell? (vals (net/net-env network))))
   :propagators (count (filter prop/prop? (vals (net/net-env network))))})

(defn- milliseconds [a b] (/ (double (- b a)) 1e6))

(defn- run-sample [{:keys [prepare expected observe]}]
  (let [start (System/nanoTime)
        compiled (prepare)
        compiled-at (System/nanoTime)
        result (runner/run-network (:props compiled) (:net compiled))
        settled-at (System/nanoTime)
        replay (runner/run-network (:props compiled) (:network result))
        replayed-at (System/nanoTime)
        actual (observe (:network result) (:cell compiled))]
    (when-not (and (= :completed (:status result) (:status replay)) (= expected actual))
      (throw (ex-info "Benchmark result mismatch"
                      {:expected expected :actual actual :error (:error result)})))
    (when-not (= (topology (:network result)) (topology (:network replay)))
      (throw (ex-info "Replay changed topology" {})))
    {:compile-ms (milliseconds start compiled-at)
     :run-ms (milliseconds compiled-at settled-at)
     :replay-ms (milliseconds settled-at replayed-at)
     :topology (topology (:network result))}))

(defn- distribution [samples key]
  (let [xs (vec (sort (map key samples)))
        n (count xs)]
    {:median (nth xs (quot n 2))
     :p10 (nth xs (quot n 10))
     :p90 (nth xs (min (dec n) (quot (* 9 n) 10)))}))

(defn- measure [{:keys [case] :as workload} warmups samples]
  (dotimes [_ warmups] (run-sample workload))
  (let [runs (vec (repeatedly samples #(run-sample workload)))]
    {:case case :warmups warmups :samples samples
     :compile-ms (distribution runs :compile-ms)
     :run-ms (distribution runs :run-ms)
     :replay-ms (distribution runs :replay-ms)
     :topology (:topology (peek runs))}))

(defn- ordinary-workloads []
  (mapv (fn [{:keys [source] :as workload}]
          (assoc workload :prepare #(compiler/compile-source source)
                 :observe observation/cell-base))
        ordinary-cases))

(defn- workloads [mode]
  (case mode
    "ordinary" (ordinary-workloads)
    "registered" (do (require 'propagators.experimental.ttms-application)
                       (ordinary-workloads))
    "native" (let [prepare (requiring-resolve 'examples.lain.amb.native-premise-selection/prepare)]
               [{:case :native-amb :expected 2
                 :prepare #(:compiled (prepare))
                 :observe observation/cell-base}])
    (throw (ex-info "Unknown benchmark mode" {:mode mode}))))

(defn- legacy-activation [path]
  ;; Evaluate only the selected function from the explicitly supplied, trusted
  ;; baseline source. Production code never uses this benchmark control.
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (binding [*read-eval* false]
      (loop [form (read {:eof ::eof} reader)]
        (cond
          (= ::eof form) (throw (ex-info "Baseline activation not found" {:path path}))
          (and (seq? form) (= 'defn- (first form))
               (= 'apply-closure-activation (second form)))
          (binding [*ns* (the-ns 'propagators.gur.flat)]
            (eval (cons 'fn (drop 2 form))))
          :else (recur (read {:eof ::eof} reader)))))))

(defn- paired-case [workload legacy warmups samples]
  (let [activation (ns-resolve 'propagators.gur.flat 'apply-closure-activation)
        current @activation
        sample (fn [implementation]
                 (with-redefs-fn {activation implementation} #(run-sample workload)))
        pair (fn [i]
               (if (even? i)
                 (let [before (sample legacy) after (sample current)] [before after])
                 (let [after (sample current) before (sample legacy)] [before after])))]
    (dotimes [i warmups] (pair i))
    (let [pairs (mapv pair (range samples))
          before (mapv first pairs) after (mapv second pairs)]
      {:case (:case workload) :warmup-pairs warmups :sample-pairs samples
       :legacy (into {} (map (fn [phase] [phase (distribution before phase)]))
                     [:compile-ms :run-ms :replay-ms])
       :current (into {} (map (fn [phase] [phase (distribution after phase)]))
                      [:compile-ms :run-ms :replay-ms])
       :paired-run-ratios (mapv (fn [[a b]] (/ (:run-ms b) (:run-ms a))) pairs)
       :topology (:topology (first after))
       :topology-equal? (every? (fn [[a b]] (= (:topology a) (:topology b))) pairs)})))

(defn -main [& [mode warmup-text sample-text baseline-path]]
  (try
    (let [mode (or mode "ordinary")
          warmups (Long/parseLong (or warmup-text "15"))
          samples (Long/parseLong (or sample-text "60"))]
      (when-not (and (pos? warmups) (pos? samples))
        (throw (ex-info "Benchmark counts must be positive" {})))
      (if (= "paired" mode)
        (let [legacy (legacy-activation baseline-path)]
          (prn {:benchmark :paired-application-dispatch
                :java (System/getProperty "java.version")
                :results (mapv #(paired-case % legacy warmups samples) (ordinary-workloads))}))
        (prn {:benchmark :application-dispatch :mode mode
              :java (System/getProperty "java.version")
              :results (mapv #(measure % warmups samples) (workloads mode))})))
    (finally (shutdown-agents))))
