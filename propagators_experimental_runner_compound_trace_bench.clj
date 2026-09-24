(ns propagators-experimental-runner-compound-trace-bench
  "Benchmark causal tracing over the real vanilla compound-object chain."
  (:require [propagators.core :as core]
            [propagators.experimental.runner.compound-chain :as chain]
            [propagators.experimental.runner.compound-trace :as trace]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(def variants [:vanilla :full-trace :scoped-trace])
(def depths [1 3 10 30])
(def warmup-rounds 5)
(def sample-rounds 20)

(defn- rotate
  [values distance]
  (let [offset (mod distance (count values))]
    (vec (concat (drop offset values) (take offset values)))))

(defn- complete
  [traced]
  (let [result (:result traced)]
    (case (:status result)
      :completed {:network (:network result) :events (:events traced)}
      :failed (throw (:error result))
      (throw (ex-info "unknown traced runner result" {:result result})))))

(defn- execute
  [variant state]
  (case variant
    :vanilla {:network (core/run-tasks (:tasks state) (:network state))
              :events []}
    :full-trace (complete (trace/run-with-trace state))
    :scoped-trace (complete (trace/run-with-scoped-trace state))
    (throw (ex-info "unknown benchmark variant" {:variant variant}))))

(defn- setup-workload
  [variant depth]
  (let [built (chain/build-vanilla-chain depth)
        executed (execute variant (select-keys built [:network :tasks]))]
    (assoc executed :input (:input built) :output (:output built))))

(defn- update-workload
  [variant fixture]
  (let [[seeded tasks] (nb/seed-cell! (:network fixture)
                                      tq/empty-queue
                                      (:input fixture)
                                      30)]
    (execute variant {:network seeded :tasks tasks})))

(defn- valid-result?
  [phase depth result fixture]
  (case phase
    :setup (let [updated (update-workload :vanilla result)]
             (and (= (if (= :vanilla (:variant result))
                       0
                       (+ (* 4 depth) 2))
                     (count (:events result)))
                  (= 30 (net/network-cell-value (:network updated)
                                                (:output result)))))
    :update (and (= 30 (net/network-cell-value (:network result)
                                                (:output fixture)))
                 (empty? (:events result)))
    false))

(defn- timed-sample
  [phase variant depth fixture]
  (let [started (System/nanoTime)
        result (case phase
                 :setup (setup-workload variant depth)
                 :update (update-workload variant fixture)
                 (throw (ex-info "unknown benchmark phase" {:phase phase})))
        elapsed (- (System/nanoTime) started)
        tagged (assoc result :variant variant)]
    (if (valid-result? phase depth tagged fixture)
      {:elapsed-ns elapsed
       :event-count (count (:events result))}
      (throw (ex-info "benchmark result failed validation"
                      {:phase phase :variant variant :depth depth})))))

(defn- run-rounds
  [round-count phase depth fixture collect?]
  (reduce
   (fn [samples round]
     (reduce
      (fn [current variant]
        (let [sample (timed-sample phase variant depth fixture)]
          (if collect?
            (update current variant conj sample)
            current)))
      samples
      (rotate variants round)))
   (zipmap variants (repeat []))
   (range round-count)))

(defn- median
  [values]
  (let [ordered (vec (sort values))]
    (nth ordered (quot (count ordered) 2))))

(defn- percentile
  [values proportion]
  (let [ordered (vec (sort values))
        index (min (dec (count ordered))
                   (long (Math/ceil (* proportion (dec (count ordered))))))]
    (nth ordered index)))

(defn- milliseconds
  [nanoseconds]
  (/ (double nanoseconds) 1000000.0))

(defn- summarize
  [phase depth variant samples]
  (let [elapsed (mapv :elapsed-ns samples)]
    {:phase phase
     :depth depth
     :variant variant
     :samples (count samples)
     :median-ms (milliseconds (median elapsed))
     :p95-ms (milliseconds (percentile elapsed 0.95))
     :min-ms (milliseconds (apply min elapsed))
     :max-ms (milliseconds (apply max elapsed))
     :event-count (:event-count (last samples))
     :correct true}))

(defn- overhead-rows
  [rows]
  (mapcat (fn [[phase depth]]
          (let [matching (filter #(and (= phase (:phase %))
                                       (= depth (:depth %)))
                                 rows)
                by-variant (into {} (map (juxt :variant identity)) matching)]
            (mapv (fn [variant]
                    {:phase phase
                     :depth depth
                     :variant variant
                     :median-overhead
                     (/ (get-in by-variant [variant :median-ms])
                        (get-in by-variant [:vanilla :median-ms]))})
                  [:full-trace :scoped-trace])))
        (for [depth depths
              phase [:setup :update]]
          [phase depth])))

(defn benchmark
  []
  (let [fixtures
        (into {}
              (for [depth depths]
                (let [setup (setup-workload :vanilla depth)]
                  [depth setup])))
        rows
        (vec
         (mapcat
          (fn [depth]
            (mapcat
             (fn [phase]
               (run-rounds warmup-rounds phase depth (fixtures depth) false)
               (let [samples (run-rounds sample-rounds
                                          phase
                                          depth
                                          (fixtures depth)
                                          true)]
                 (mapv #(summarize phase depth % (get samples %)) variants)))
             [:setup :update]))
          depths))]
    {:configuration {:warmup-rounds warmup-rounds
                     :sample-rounds sample-rounds
                     :depths depths
                     :variant-order variants
                     :forced-gc false}
     :results rows
     :overhead (overhead-rows rows)}))

(defn- format-number
  [number]
  (String/format java.util.Locale/US
                 "%.4f"
                 (to-array [(double number)])))

(defn- print-report
  [{:keys [results overhead]}]
  (println "phase   depth variant   median-ms     p95-ms events")
  (doseq [{:keys [phase depth variant median-ms p95-ms event-count]} results]
    (println (format "%-7s %5d %-8s %10s %10s %6d"
                     (name phase)
                     depth
                     (name variant)
                     (format-number median-ms)
                     (format-number p95-ms)
                     event-count)))
  (println "overhead ratios (trace variant / vanilla median)")
  (doseq [{:keys [phase depth variant median-overhead]} overhead]
    (println (format "%-7s depth=%-3d %-12s %sx"
                     (name phase)
                     depth
                     (name variant)
                     (format-number median-overhead)))))

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
