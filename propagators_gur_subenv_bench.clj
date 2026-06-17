(ns propagators-gur-subenv-bench
  "Benchmark lexical sub-env GUR recursive examples.

  Usage:
    clojure -M:gur-subenv-bench
    clojure -M:gur-subenv-bench 5 20  ; warmups iterations"
  (:require [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur.subenv :as subenv]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

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

(defn- strongest
  [n id]
  (net/network-cell-strongest n id))

(defn- run-props
  [n prop-ids]
  (core/run-tasks (tq/enqueue-all tq/empty-queue prop-ids) n))

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

(defn- observe-first-two-heads
  [network collection-id]
  (let [head0-id (ids/new-node-id)
        tail0-id (ids/new-node-id)
        head1-id (ids/new-node-id)
        tail1-id (ids/new-node-id)
        n0 (reduce nb/install-cell
                   network
                   [head0-id tail0-id head1-id tail1-id])
        [head0-prop n1] ((obj/p:car head0-id collection-id) n0)
        [tail0-prop n2] ((obj/p:cdr tail0-id collection-id) n1)
        [head1-prop n3] ((obj/p:car head1-id tail0-id) n2)
        [tail1-prop n4] ((obj/p:cdr tail1-id tail0-id) n3)
        n5 (run-props n4 [head0-prop tail0-prop head1-prop tail1-prop])]
    {:net n5
     :head-ids [head0-id head1-id]}))

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

(defn- setup-incremental-map
  []
  (let [fib-id (ids/new-node-id)
        map-id (ids/new-node-id)
        list-id (ids/new-node-id)
        head0-id (ids/new-node-id)
        tail0-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell fib-id (subenv/fib-closure) (subenv/fib-closure))
               (nb/install-cell map-id (subenv/map-list-closure) (subenv/map-list-closure))
               (nb/install-cell list-id)
               (nb/install-cell head0-id 0 0)
               (nb/install-cell tail0-id)
               (nb/install-cell acc-id subenv/empty-list subenv/empty-list)
               (nb/install-cell out-id))
        [[car0-prop cdr0-prop] n1] ((obj/p:cons head0-id tail0-id list-id) n0)
        [map-props n2] ((subenv/p:apply-closure map-id
                                               [list-id fib-id acc-id]
                                               out-id)
                        n1)
        n3 (run-props n2 (concat [car0-prop cdr0-prop] map-props))
        {n4 :net [_ mapped-head1-id] :head-ids} (observe-first-two-heads n3 out-id)
        head1-id (ids/new-node-id)
        tail1-id (ids/new-node-id)
        n5 (-> n4
               (nb/install-cell head1-id 1 1)
               (nb/install-cell tail1-id))
        [[car1-prop cdr1-prop] n6] ((obj/p:cons head1-id tail1-id tail0-id) n5)]
    {:net n6
     :props [car1-prop cdr1-prop]
     :value-id mapped-head1-id
     :expect 1}))

(defn- setup-incremental-reduce
  []
  (let [reduce-id (ids/new-node-id)
        step-id (ids/new-node-id)
        list-id (ids/new-node-id)
        head0-id (ids/new-node-id)
        tail0-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell reduce-id
                                (subenv/reduce-list-closure)
                                (subenv/reduce-list-closure))
               (nb/install-cell step-id
                                (subenv/sum-step-closure)
                                (subenv/sum-step-closure))
               (nb/install-cell list-id)
               (nb/install-cell head0-id 1 1)
               (nb/install-cell tail0-id)
               (nb/install-cell acc-id 0 0)
               (nb/install-cell out-id))
        [[car0-prop cdr0-prop] n1] ((obj/p:cons head0-id tail0-id list-id) n0)
        [reduce-props n2] ((subenv/p:apply-closure reduce-id
                                                  [list-id step-id acc-id]
                                                  out-id)
                           n1)
        n3 (run-props n2 (concat [car0-prop cdr0-prop] reduce-props))
        head1-id (ids/new-node-id)
        tail1-id (ids/new-node-id)
        n4 (-> n3
               (nb/install-cell head1-id 2 2)
               (nb/install-cell tail1-id subenv/empty-list subenv/empty-list))
        [[car1-prop cdr1-prop] n5] ((obj/p:cons head1-id tail1-id tail0-id) n4)]
    {:net n5
     :props [car1-prop cdr1-prop]
     :value-id out-id
     :expect 3}))

(defn- setup-incremental-filter
  []
  (let [filter-id (ids/new-node-id)
        predicate-id (ids/new-node-id)
        list-id (ids/new-node-id)
        head0-id (ids/new-node-id)
        tail0-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell filter-id
                                (subenv/filter-list-closure)
                                (subenv/filter-list-closure))
               (nb/install-cell predicate-id
                                (subenv/even-predicate-closure)
                                (subenv/even-predicate-closure))
               (nb/install-cell list-id)
               (nb/install-cell head0-id 0 0)
               (nb/install-cell tail0-id)
               (nb/install-cell acc-id subenv/empty-list subenv/empty-list)
               (nb/install-cell out-id))
        [[car0-prop cdr0-prop] n1] ((obj/p:cons head0-id tail0-id list-id) n0)
        [filter-props n2] ((subenv/p:apply-closure filter-id
                                                  [list-id predicate-id acc-id]
                                                  out-id)
                           n1)
        n3 (run-props n2 (concat [car0-prop cdr0-prop] filter-props))
        {n4 :net [_ mapped-head1-id] :head-ids} (observe-first-two-heads n3 out-id)
        head1-id (ids/new-node-id)
        tail1-id (ids/new-node-id)
        n5 (-> n4
               (nb/install-cell head1-id 2 2)
               (nb/install-cell tail1-id subenv/empty-list subenv/empty-list))
        [[car1-prop cdr1-prop] n6] ((obj/p:cons head1-id tail1-id tail0-id) n5)]
    {:net n6
     :props [car1-prop cdr1-prop]
     :value-id mapped-head1-id
     :expect 2}))

(defn- incremental-cases
  []
  [{:label "incremental map late cdr [0 . ?] -> [0 1]"
    :setup setup-incremental-map}
   {:label "incremental reduce late terminal cdr [1 . ?] -> [1 2]"
    :setup setup-incremental-reduce}
   {:label "incremental filter late cdr [0 . ?] -> [0 2]"
    :setup setup-incremental-filter}])

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

(defn- bench-incremental-case
  [warmup iters {:keys [label setup]}]
  (dotimes [_ warmup]
    (let [{:keys [net props]} (setup)]
      (run-props net props)))
  (let [sample (fn []
                 (let [{:keys [net props value-id expect]} (setup)]
                   (time-ns
                    (fn []
                      (let [n' (run-props net props)]
                        {:net n'
                         :observed (strongest n' value-id)
                         :expect expect})))))
        samples (vec (repeatedly iters sample))
        nss (map :ns samples)
        last-result (:result (last samples))]
    {:label label
     :iters iters
     :mean-ms (/ (mean nss) 1e6)
     :median-ms (/ (median nss) 1e6)
     :min-ms (/ (apply min nss) 1e6)
     :max-ms (/ (apply max nss) 1e6)
     :parent-cells (count (net/net-env (:net last-result)))
     :ok (= (:expect last-result) (:observed last-result))}))

(defn -main
  [& args]
  (let [warmup (if-let [x (first args)] (Long/parseLong x) 5)
        iters (if-let [x (second args)] (Long/parseLong x) 20)
        rows (mapv #(bench-case warmup iters %) (cases))]
    (println "lexical sub-env GUR benchmark")
    (println (str "warmup=" warmup " iterations=" iters))
    (println "end-to-end expansion/run")
    (doseq [row rows]
      (print-row row))
    (println "incremental late-update propagation")
    (let [incremental-rows (mapv #(bench-incremental-case warmup iters %)
                                 (incremental-cases))]
      (doseq [row incremental-rows]
        (print-row row))
      (when-not (and (every? :ok rows)
                     (every? :ok incremental-rows))
        (throw (ex-info "benchmark result check failed"
                        {:rows rows
                         :incremental-rows incremental-rows}))))))
