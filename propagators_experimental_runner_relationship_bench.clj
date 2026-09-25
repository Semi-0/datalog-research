(ns propagators-experimental-runner-relationship-bench
  "Compare untracked, scan-based, and embedded-relationship execution."
  (:require [propagators.experimental.runner.compound-chain :as chain]
            [propagators.experimental.runner.compound-trace :as trace]
            [propagators.experimental.runner.examples :as examples]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]))

(def variants [:untracked :scoped-diff :relationship])
(def depths [1 3 10 30])
(def warmup-rounds 5)
(def sample-rounds 20)

(defn- complete-result
  [result]
  (case (:status result)
    :completed (:network result)
    :failed (throw (:error result))
    (throw (ex-info "unknown runner result" {:result result}))))

(defn- relationship-edge-count
  [network]
  (reduce + 0
          (map #(count (graph/node-output-ids %))
               (vals (net/net-relationship network)))))

(defn- execute
  [variant state]
  (case variant
    :untracked
    {:network (complete-result (examples/normal-runner state))
     :events []}

    :scoped-diff
    (let [{:keys [result events]} (trace/run-with-scoped-trace state)]
      {:network (complete-result result)
       :events events})

    :relationship
    {:network (complete-result
               (runner/run-network (:tasks state) (:network state)))
     :events []}

    (throw (ex-info "unknown benchmark variant" {:variant variant}))))

(defn- setup
  [variant depth]
  (let [built (chain/build-vanilla-chain depth)]
    (merge (execute variant (select-keys built [:network :tasks]))
           (select-keys built [:input :output]))))

(defn- update-chain
  [variant fixture]
  (let [[seeded tasks] (nb/seed-cell! (:network fixture)
                                      tq/empty-queue
                                      (:input fixture)
                                      30)]
    (execute variant {:network seeded :tasks tasks})))

(defn- valid?
  [phase variant depth fixture result]
  (let [edges (relationship-edge-count (:network result))]
    (case phase
      :setup
      (and (= (if (= variant :scoped-diff) (+ (* 4 depth) 2) 0)
              (count (:events result)))
           (= (if (= variant :relationship) true false)
              (pos? edges))
           (= 30 (net/network-cell-value
                  (:network (update-chain variant result))
                  (:output result))))

      :update
      (and (= 30 (net/network-cell-value (:network result)
                                         (:output fixture)))
           (empty? (:events result))
           (= (relationship-edge-count (:network fixture)) edges))

      false)))

(defn- timed-sample
  [phase variant depth fixture]
  (let [started (System/nanoTime)
        result (case phase
                 :setup (setup variant depth)
                 :update (update-chain variant fixture)
                 (throw (ex-info "unknown benchmark phase" {:phase phase})))
        elapsed (- (System/nanoTime) started)]
    (if (valid? phase variant depth fixture result)
      {:elapsed-ns elapsed
       :event-count (count (:events result))
       :relationship-edges (relationship-edge-count (:network result))}
      (throw (ex-info "benchmark result failed validation"
                      {:phase phase :variant variant :depth depth})))))

(defn- collect-samples
  [round-count phase variant depth fixture collect?]
  (reduce
   (fn [samples _round]
     (let [sample (timed-sample phase variant depth fixture)]
       (if collect?
         (conj samples sample)
         samples)))
   []
   (range round-count)))

(defn- percentile
  [values proportion]
  (let [ordered (vec (sort values))
        index (min (dec (count ordered))
                   (long (Math/ceil (* proportion (dec (count ordered))))))]
    (nth ordered index)))

(defn- summarize
  [phase depth variant samples]
  (let [elapsed (mapv :elapsed-ns samples)]
    {:phase phase
     :depth depth
     :variant variant
     :samples (count samples)
     :median-ms (/ (double (percentile elapsed 0.5)) 1000000.0)
     :p95-ms (/ (double (percentile elapsed 0.95)) 1000000.0)
     :event-count (:event-count (last samples))
     :relationship-edges (:relationship-edges (last samples))
     :correct true}))

(defn benchmark
  []
  (let [fixtures
        (into {}
              (for [depth depths
                    variant variants]
                [[variant depth] (setup variant depth)]))
        rows
        (vec
         (mapcat
          (fn [depth]
            (mapcat
             (fn [phase]
               (let [fixture-for #(get fixtures [% depth])]
                 (doseq [variant variants]
                   (collect-samples warmup-rounds
                                    phase
                                    variant
                                    depth
                                    (fixture-for variant)
                                    false))
                 (mapv
                  (fn [variant]
                    (let [samples
                          (collect-samples sample-rounds
                                           phase
                                           variant
                                           depth
                                           (fixture-for variant)
                                           true)]
                      (summarize phase depth variant samples)))
                  variants)))
             [:setup :update]))
          depths))]
    {:configuration {:warmup-rounds warmup-rounds
                     :sample-rounds sample-rounds
                     :depths depths
                     :forced-gc false}
     :results rows}))

(defn- formatted
  [number]
  (String/format java.util.Locale/US "%.4f" (to-array [(double number)])))

(defn- print-report
  [report]
  (println "phase   depth variant          median-ms     p95-ms events edges")
  (doseq [{:keys [phase depth variant median-ms p95-ms event-count
                  relationship-edges]}
          (:results report)]
    (println (format "%-7s %5d %-16s %10s %10s %6d %5d"
                     (name phase)
                     depth
                     (name variant)
                     (formatted median-ms)
                     (formatted p95-ms)
                     event-count
                     relationship-edges))))

(defn -main
  [& args]
  (if (empty? args)
    (let [report (benchmark)]
      (print-report report)
      (println "EDN")
      (prn report)
      (shutdown-agents))
    (throw (ex-info "this benchmark accepts no arguments"
                    {:arguments (vec args)}))))
