(ns propagators-experimental-runner-semantic-trace-bench
  "Compare vanilla, always-on scoped tracing, and semantic topology tracing."
  (:require [propagators.experimental.runner.compound-chain :as chain]
            [propagators.experimental.runner.compound-trace :as trace]
            [propagators.experimental.runner.semantic-compound-trace :as semantic]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(def variants [:vanilla :scoped-trace :semantic-trace])
(def depths [1 3 10 30])
(def warmup-rounds 5)
(def sample-rounds 20)

(defn- completed
  [execution]
  (let [result (:result execution)]
    (case (:status result)
      :completed {:network (:network result)
                  :events (:events execution)
                  :metrics (:metrics execution)}
      :failed (throw (:error result))
      (throw (ex-info "unknown runner result" {:result result})))))

(defn- execute
  [variant state]
  (case variant
    :vanilla {:network (nb/run-propagators (:network state) (:tasks state))
              :events []
              :metrics {}}
    :scoped-trace (completed (trace/run-with-scoped-trace state))
    :semantic-trace (completed (semantic/run-with-semantic-trace state))
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
  [phase variant depth result]
  (case phase
    :setup (and (= (if (= variant :vanilla) 0 (+ (* 4 depth) 2))
                   (count (:events result)))
                (= 30 (net/network-cell-value
                       (:network (update-chain :vanilla result))
                       (:output result))))
    :update (and (= 30 (net/network-cell-value (:network result)
                                                (:output result)))
                 (empty? (:events result))
                 (if (= variant :semantic-trace)
                   (zero? (get-in result [:metrics :topology-diffs] 0))
                   true))
    false))

(defn- timed-sample
  [phase variant depth fixture]
  (let [started (System/nanoTime)
        raw-result (case phase
                     :setup (setup variant depth)
                     :update (update-chain variant fixture)
                     (throw (ex-info "unknown benchmark phase" {:phase phase})))
        elapsed (- (System/nanoTime) started)
        result (if (= phase :update)
                 (assoc raw-result :output (:output fixture))
                 raw-result)]
    (if (valid? phase variant depth result)
      {:elapsed-ns elapsed
       :event-count (count (:events result))
       :topology-diffs (get-in result [:metrics :topology-diffs] 0)}
      (throw (ex-info "benchmark result failed validation"
                      {:phase phase :variant variant :depth depth})))))

(defn- rotate
  [values distance]
  (let [offset (mod distance (count values))]
    (vec (concat (drop offset values) (take offset values)))))

(defn- collect-samples
  [round-count phase depth fixture collect?]
  (reduce
   (fn [samples round]
     (reduce (fn [current variant]
               (let [sample (timed-sample phase variant depth fixture)]
                 (if collect?
                   (update current variant conj sample)
                   current)))
             samples
             (rotate variants round)))
   (zipmap variants (repeat []))
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
     :topology-diffs (:topology-diffs (last samples))
     :correct true}))

(defn benchmark
  []
  (let [fixtures (into {} (map (fn [depth] [depth (setup :vanilla depth)])) depths)
        rows (vec
              (mapcat
               (fn [depth]
                 (mapcat
                  (fn [phase]
                    (collect-samples warmup-rounds phase depth (fixtures depth) false)
                    (let [samples (collect-samples sample-rounds
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
                     :forced-gc false}
     :results rows}))

(defn- formatted
  [number]
  (String/format java.util.Locale/US "%.4f" (to-array [(double number)])))

(defn- print-report
  [report]
  (println "phase   depth variant          median-ms     p95-ms events diffs")
  (doseq [{:keys [phase depth variant median-ms p95-ms event-count topology-diffs]}
          (:results report)]
    (println (format "%-7s %5d %-16s %10s %10s %6d %5d"
                     (name phase)
                     depth
                     (name variant)
                     (formatted median-ms)
                     (formatted p95-ms)
                     event-count
                     topology-diffs))))

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
