(ns propagators-gur-subenv-bench
  "Benchmark lexical sub-env GUR recursive examples.

  Usage:
    clojure -M:gur-subenv-bench
    clojure -M:gur-subenv-bench 5 20  ; warmups iterations"
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur.subenv :as subenv]
            [propagators.network :as net]))

(defn- fmt-ms
  [x]
  (String/format java.util.Locale/US "%.3f" (to-array [(double x)])))

(defn- mean
  [xs]
  (/ (reduce + 0 xs) (count xs)))

(defn- median
  [xs]
  (let [xs (vec (sort xs))]
    (nth xs (quot (count xs) 2))))

(defn- time-ns
  [f]
  (let [t0 (System/nanoTime)
        result (f)]
    {:result result
     :ns (- (System/nanoTime) t0)}))

(defn- list-slot
  [v slot-key]
  (cond
    (value/unusable? v)
    v

    (and (net/net? v) (obj/accessor-source-slot-present? v slot-key))
    (obj/accessor-source-slot-value v slot-key)

    (net/net? v)
    (or (obj/slot-value v slot-key) value/nothing)

    :else
    value/nothing))

(defn- list->vec
  ([v] (list->vec v 64))
  ([v limit]
   (loop [current v
          remaining limit
          acc []]
     (cond
       (zero? remaining) acc
       (subenv/empty-list? current) acc
       (value/unusable? current) acc
       :else (recur (list-slot current :cdr)
                    (dec remaining)
                    (conj acc (list-slot current :car)))))))

(defn- list->data
  [v]
  (if (subenv/list-node-value? v)
    (mapv list->data (list->vec v))
    v))

(defn- cases
  []
  [{:label "fib(6)"
    :run #(subenv/run-fib 6)
    :value :value
    :expect 8}
   {:label "factorial(5)"
    :run #(subenv/run-factorial 5)
    :value :value
    :expect 120}
   {:label "int-sqrt(81)"
    :run #(subenv/run-int-sqrt 81)
    :value :value
    :expect 9}
   {:label "map-list-fib [0..5]"
    :run #(subenv/run-map-list-fib [0 1 2 3 4 5])
    :value #(list->vec (:value %))
    :expect [0 1 1 2 3 5]}
   {:label "nested-map-list-fib [[0 1] [2 3]]"
    :run #(subenv/run-nested-map-list-fib [(subenv/cons-list-value [0 1])
                                           (subenv/cons-list-value [2 3])])
    :value #(list->data (:value %))
    :expect [[0 1] [1 2]]}
   {:label "reduce-list-sum [0..19]"
    :run #(subenv/run-reduce-list-sum (vec (range 20)))
    :value :value
    :expect 190}
   {:label "filter-list-even [0..19]"
    :run #(subenv/run-filter-list-even (vec (range 20)))
    :value #(list->vec (:value %))
    :expect [0 2 4 6 8 10 12 14 16 18]}])

(defn- bench-case
  [warmup iters {:keys [label run value expect]}]
  (dotimes [_ warmup] (run))
  (let [samples (vec (repeatedly iters #(time-ns run)))
        nss (map :ns samples)
        last-result (:result (last samples))
        observed (if (keyword? value)
                   (get last-result value)
                   (value last-result))]
    {:label label
     :iters iters
     :mean-ms (/ (mean nss) 1e6)
     :median-ms (/ (median nss) 1e6)
     :min-ms (/ (apply min nss) 1e6)
     :max-ms (/ (apply max nss) 1e6)
     :parent-cells (count (net/net-env (:net last-result)))
     :ok (= expect observed)}))

(defn- print-row
  [{:keys [label iters mean-ms median-ms min-ms max-ms parent-cells ok]}]
  (println
   (str label
        "\titers=" iters
        "\tmedian=" (fmt-ms median-ms) "ms"
        "\tmean=" (fmt-ms mean-ms) "ms"
        "\tmin=" (fmt-ms min-ms) "ms"
        "\tmax=" (fmt-ms max-ms) "ms"
        "\tparent-cells=" parent-cells
        "\tok=" ok)))

(defn -main
  [& args]
  (let [warmup (if-let [x (first args)] (Long/parseLong x) 5)
        iters (if-let [x (second args)] (Long/parseLong x) 20)
        rows (mapv #(bench-case warmup iters %) (cases))]
    (println "lexical sub-env GUR benchmark")
    (println (str "warmup=" warmup " iterations=" iters))
    (doseq [row rows]
      (print-row row))
    (when-not (every? :ok rows)
      (throw (ex-info "benchmark result check failed" {:rows rows})))))
