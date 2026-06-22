(ns propagators-gur-accumulating-bench
  "Small benchmark for the current accumulating GUR HOP chain path.

  Usage:
    clojure -M:gur-accumulating-bench
    clojure -M:gur-accumulating-bench 2 5  ; warmups iterations"
  (:require [propagators.gur-accumulating-test]))

(defn- fmt-ms [x]
  (String/format java.util.Locale/US "%.3f" (to-array [(double x)])))

(defn- mean [xs]
  (/ (reduce + 0 xs) (count xs)))

(defn- median [xs]
  (let [xs (vec (sort xs))]
    (nth xs (quot (count xs) 2))))

(defn- test-var [sym]
  @(ns-resolve 'propagators.gur-accumulating-test sym))

(def run-list-hop-chain (test-var 'run-list-hop-chain))
(def list->vec (test-var 'list->vec))
(def map-list (test-var 'map-list))
(def filter-list (test-var 'filter-list))
(def double-value (test-var 'double-value))
(def even-predicate (test-var 'even-predicate))
(def empty-list (test-var 'subenv/empty-list))

(defn- time-ns [f]
  (let [t0 (System/nanoTime)
        result (f)]
    {:result result
     :ns (- (System/nanoTime) t0)}))

(defn- mapper-case [depth]
  {:label (str "accumulating map HOP depth " depth)
   :run #(run-list-hop-chain map-list double-value [1 1 1 1 1] depth)
   :value #(list->vec (:value %))
   :expect (vec (repeat 5 (long (Math/pow 2 depth))))})

(defn- filter-case [depth]
  {:label (str "accumulating filter HOP depth " depth)
   :run #(run-list-hop-chain filter-list
                             even-predicate
                             [1 2 3 4 5 6]
                             depth
                             empty-list
                             empty-list)
   :value #(list->vec (:value %))
   :expect [2 4 6]})

(defn- cases []
  [(mapper-case 5)
   (mapper-case 10)
   (mapper-case 15)
   (filter-case 5)
   (filter-case 10)])

(defn- bench-case [warmup iters {:keys [label run value expect]}]
  (dotimes [_ warmup] (run))
  (let [samples (vec (repeatedly iters #(time-ns run)))
        nss (map :ns samples)
        observed (value (:result (last samples)))]
    {:label label
     :iters iters
     :median-ms (/ (median nss) 1e6)
     :mean-ms (/ (mean nss) 1e6)
     :min-ms (/ (apply min nss) 1e6)
     :max-ms (/ (apply max nss) 1e6)
     :ok (= expect observed)}))

(defn- print-row [{:keys [label iters median-ms mean-ms min-ms max-ms ok]}]
  (println
   (str label
        "\titers=" iters
        "\tmedian=" (fmt-ms median-ms) "ms"
        "\tmean=" (fmt-ms mean-ms) "ms"
        "\tmin=" (fmt-ms min-ms) "ms"
        "\tmax=" (fmt-ms max-ms) "ms"
        "\tok=" ok)))

(defn -main [& args]
  (let [warmup (if-let [x (first args)] (Long/parseLong x) 1)
        iters (if-let [x (second args)] (Long/parseLong x) 3)
        rows (mapv #(bench-case warmup iters %) (cases))]
    (println "accumulating GUR HOP benchmark")
    (println (str "warmup=" warmup " iterations=" iters))
    (doseq [row rows] (print-row row))
    (when-not (every? :ok rows)
      (throw (ex-info "benchmark result check failed" {:rows rows})))))
