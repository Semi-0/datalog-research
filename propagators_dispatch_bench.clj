(ns propagators-dispatch-bench
  "Benchmark generic and layered procedure dispatch.

  Usage:
    clojure -M:dispatch-bench
    clojure -M:dispatch-bench 50 1
    clojure -M:dispatch-bench 50 51"
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.generic-procedure :as generic]
            [propagators.ids :refer [new-node-id]]
            [propagators.layered :as layered]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.stdlib.provenance-arithmetic :as prov-arith]))

(defn- fmt-ms [x]
  (String/format java.util.Locale/US "%.3f" (to-array [(double x)])))

(defn- mean [xs]
  (/ (reduce + 0 xs) (count xs)))

(defn- median [xs]
  (let [xs (vec (sort xs))]
    (nth xs (quot (count xs) 2))))

(defn- time-ns [f]
  (let [t0 (System/nanoTime)
        result (f)]
    {:result result
     :ns (- (System/nanoTime) t0)}))

(defn- bench-iters
  [label warmup iters f]
  (dotimes [_ warmup] (f))
  (let [samples (vec (repeatedly iters #(time-ns f)))
        nss (map :ns samples)]
    {:label label
     :iters iters
     :mean-ms (/ (mean nss) 1e6)
     :median-ms (/ (median nss) 1e6)
     :min-ms (/ (apply min nss) 1e6)
     :max-ms (/ (apply max nss) 1e6)
     :last-result (:result (last samples))}))

(defn- print-row [{:keys [label iters mean-ms median-ms min-ms max-ms last-result]}]
  (println
   (str label
        "\titers=" iters
        "\tmedian=" (fmt-ms median-ms) "ms"
        "\tmean=" (fmt-ms mean-ms) "ms"
        "\tmin=" (fmt-ms min-ms) "ms"
        "\tmax=" (fmt-ms max-ms) "ms"
        "\tok=" (:ok last-result))))

(defn- install-only [n installer]
  (second (installer n)))

(defn- installed-cells [& ids]
  (reduce nb/install-cell net/empty-net ids))

(defn- build-generic-procedure
  [handler-count]
  (let [generic-id (new-node-id)
        default-id (new-node-id)
        n0 (-> (installed-cells generic-id default-id)
               (nb/seed-cell default-id value/nothing))
        n1 (install-only n0 (generic/make-generic-propagator generic-id default-id))
        n2 (reduce
            (fn [n i]
              (install-only
               n
               (generic/define-generic-propagator-handler
                generic-id
                (generic/match-cells-pred #(= i %))
                (generic/handler-closure (fn [x] [:exact i x])))))
            n1
            (range handler-count))]
    {:net n2
     :generic-id generic-id}))

(defn- apply-generic-round
  [n generic-id arg-value]
  (let [arg-id (new-node-id)
        out-id (new-node-id)
        n0 (-> n
               (nb/install-cell arg-id)
               (nb/install-cell out-id)
               (nb/seed-cell arg-id arg-value))
        [apply-prop n1] ((generic/p:apply-generic generic-id [arg-id] out-id) n0)
        n2 (nb/run-propagators n1 [apply-prop])]
    {:net n2
     :value (net/network-cell-strongest n2 out-id)}))

(defn- run-generic-rounds
  [handler-count rounds]
  (let [{:keys [net generic-id]} (build-generic-procedure handler-count)
        args (vec (concat (range handler-count)
                          (repeat (max 0 (- rounds handler-count)) handler-count)))]
    (reduce
     (fn [{:keys [net values]} arg-value]
       (let [{net' :net value :value} (apply-generic-round net generic-id arg-value)]
         {:net net'
          :values (conj values value)}))
     {:net net :values []}
     (take rounds args))))

(defn- install-layered-inputs
  [n a b]
  (let [a-base (new-node-id)
        a-prov (new-node-id)
        b-base (new-node-id)
        b-prov (new-node-id)
        n0 (reduce nb/install-cell n [a-base a-prov b-base b-prov])
        [a-base-prop n1] ((layered/p:base a-base a) n0)
        [a-prov-prop n2] ((layered/p:layer :provenance a-prov a) n1)
        [b-base-prop n3] ((layered/p:base b-base b) n2)
        [b-prov-prop n4] ((layered/p:layer :provenance b-prov b) n3)]
    {:net n4
     :props [a-base-prop a-prov-prop b-base-prop b-prov-prop]
     :a-base a-base
     :a-prov a-prov
     :b-base b-base
     :b-prov b-prov}))

(defn- apply-layered-round
  [n operator left right]
  (let [a (new-node-id)
        b (new-node-id)
        out (new-node-id)
        n0 (reduce nb/install-cell n [a b out])
        input (install-layered-inputs n0 a b)
        [apply-prop n1] ((operator a b out) (:net input))
        n2 (-> n1
               (nb/seed-cell (:a-base input) left)
               (nb/seed-cell (:a-prov input) #{:left})
               (nb/seed-cell (:b-base input) right)
               (nb/seed-cell (:b-prov input) #{:right})
               (nb/run-propagators (conj (:props input) apply-prop)))
        out-object (net/network-cell-strongest n2 out)]
    {:net n2
     :base (obj/slot-value out-object :base)
     :provenance (obj/slot-value out-object :provenance)}))

(defn- run-layered-rounds
  [rounds]
  (let [{:keys [net operator]} (prov-arith/+ net/empty-net)]
    (reduce
     (fn [{:keys [net values]} i]
       (let [{net' :net :as result} (apply-layered-round net operator i (inc i))]
         {:net net'
          :values (conj values [(:base result) (:provenance result)])}))
     {:net net :values []}
     (range rounds))))

(defn- generic-ok?
  [handler-count rounds values]
  (let [expected (vec (concat (mapv (fn [i] [:exact i i])
                                    (range (min handler-count rounds)))
                              (repeat (max 0 (- rounds handler-count))
                                      value/nothing)))]
    (= expected values)))

(defn- layered-ok?
  [values]
  (every? (fn [[base provenance]]
            (and (number? base)
                 (= #{:left :right} provenance)))
          values))

(defn -main [& args]
  (let [handler-count (if-let [x (first args)] (Long/parseLong x) 50)
        rounds (if-let [x (second args)] (Long/parseLong x) 1)
        warmup 1
        iters 5
        generic (bench-iters
                 (str "generic " handler-count " handlers / " rounds " dispatches")
                 warmup
                 iters
                 #(let [{:keys [values]} (run-generic-rounds handler-count rounds)]
                    {:ok (generic-ok? handler-count rounds values)}))
        layered (bench-iters
                 (str "layered base+provenance / " rounds " dispatches")
                 warmup
                 iters
                 #(let [{:keys [values]} (run-layered-rounds rounds)]
                    {:ok (layered-ok? values)}))]
    (println "procedure dispatch benchmark")
    (print-row generic)
    (print-row layered)))
