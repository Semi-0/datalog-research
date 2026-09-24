(ns propagators-experimental-runner-compound-bench
  "Small comparative benchmark for experimental compound-chain runners."
  (:require [propagators.experimental.runner.compound-chain :as chain]))

(def variants [:vanilla :port :closure])
(def depths [1 3 10 30])
(def warmup-rounds 5)
(def sample-rounds 20)

(defn- rotate
  [values distance]
  (let [size (count values)
        offset (mod distance size)]
    (vec (concat (drop offset values) (take offset values)))))

(defn- timed
  [f]
  (let [started (System/nanoTime)
        result (f)]
    {:elapsed-ns (- (System/nanoTime) started)
     :result result}))

(defn- checked-setup
  [variant depth]
  (chain/prepare-chain variant depth))

(defn- checked-update
  [prepared]
  (let [result (chain/run-update prepared 30)]
    (if (:ok result)
      result
      (throw (ex-info "update produced an invalid result"
                      {:variant (:variant prepared) :result result})))))

(defn- workload
  [phase variant depth fixtures]
  (case phase
    :setup #(checked-setup variant depth)
    :update #(checked-update (get fixtures [variant depth]))
    (throw (ex-info "unknown benchmark phase" {:phase phase}))))

(defn- run-rounds
  [round-count phase depth fixtures collect?]
  (reduce
   (fn [samples round]
     (reduce
      (fn [current variant]
        (let [measurement (timed (workload phase variant depth fixtures))]
          (if (= phase :setup)
            (checked-update (:result measurement))
            nil)
          (if collect?
            (update current variant conj measurement)
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
  [phase depth variant measurements fixtures]
  (let [elapsed (mapv :elapsed-ns measurements)
        runtime-metrics (if (= phase :update)
                          (:metrics (:result (last measurements)))
                          {})]
    {:phase phase
     :depth depth
     :variant variant
     :samples (count elapsed)
     :median-ms (milliseconds (median elapsed))
     :p95-ms (milliseconds (percentile elapsed 0.95))
     :min-ms (milliseconds (apply min elapsed))
     :max-ms (milliseconds (apply max elapsed))
     :topology (chain/topology-metrics (get fixtures [variant depth]))
     :runtime-metrics runtime-metrics
     :correct true}))

(defn benchmark
  []
  (let [fixtures (into {}
                       (for [variant variants
                             depth depths]
                         [[variant depth] (checked-setup variant depth)]))]
    (vec
     (mapcat
      (fn [depth]
        (mapcat
         (fn [phase]
           (run-rounds warmup-rounds phase depth fixtures false)
           (let [samples (run-rounds sample-rounds phase depth fixtures true)]
             (mapv #(summarize phase depth % (get samples %) fixtures)
                   variants)))
         [:setup :update]))
      depths))))

(defn- format-ms
  [number]
  (String/format java.util.Locale/US
                 "%.4f"
                 (to-array [(double number)])))

(defn- print-row
  [{:keys [phase depth variant median-ms p95-ms topology runtime-metrics]}]
  (println (format "%-7s %5d %-8s %10s %10s %6d %6d %6d  %s"
                   (name phase)
                   depth
                   (name variant)
                   (format-ms median-ms)
                   (format-ms p95-ms)
                   (:cells topology)
                   (:propagators topology)
                   (:dict-entries topology)
                   (pr-str runtime-metrics))))

(defn- print-table
  [rows]
  (println "phase   depth variant   median-ms     p95-ms  cells  props   dict  update-metrics")
  (doseq [row rows]
    (print-row row)))

(def design-assessment
  {:constructor-modifications 0
   :generic-runner-domain-branches 0
   :port-specific-adapters 1
   :closure-specific-adapters 1
   :port-inner-suspension false
   :closure-inner-suspension false
   :closure-inner-state-retained false
   :recursive-closures-supported false})

(defn -main
  [& args]
  (if (empty? args)
    (let [rows (benchmark)
          report {:configuration {:warmup-rounds warmup-rounds
                                  :sample-rounds sample-rounds
                                  :depths depths
                                  :variant-order variants
                                  :forced-gc false}
                  :design design-assessment
                  :results rows}]
      (print-table rows)
      (println "EDN")
      (prn report)
      (shutdown-agents))
    (throw (ex-info "this benchmark accepts no arguments"
                    {:arguments (vec args)}))))
