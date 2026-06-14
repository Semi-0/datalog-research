(ns propagators-kernel-io-bench
  "Benchmark the IO-carrying kernel and lexical compound boundary.

  Usage:
    clojure -M:kernel-io-bench

  The first two rows compare equivalent bidirectional identity boundaries:
  legacy runtime compound uses the historical compound/diff-cell path; lexical
  compound uses inbox/outbox reality propagators and `runtime/continue`."
  (:require [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.lexical-compound :as lexical]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :refer [compound-propagator]]
            [propagators.reality :as reality]
            [propagators.stdlib.boundary :refer [bi-sync-closure]]
            [propagators.stdlib.prop :as prop]))

(defn- ms [ns]
  (/ (double ns) 1e6))

(defn- fmt [x]
  (String/format java.util.Locale/US "%.3f" (to-array [(double x)])))

(defn- mean [xs]
  (/ (reduce + 0 xs) (count xs)))

(defn- median [xs]
  (let [xs (vec (sort xs))]
    (nth xs (quot (count xs) 2))))

(defn- time-ns [f]
  (let [t0 (System/nanoTime)
        result (f)]
    {:result result :ns (- (System/nanoTime) t0)}))

(defn- bench-iters [label warmup iters f]
  (dotimes [_ warmup] (f))
  (let [samples (vec (repeatedly iters #(time-ns f)))
        nss (map :ns samples)]
    {:label label
     :iters iters
     :mean-ms (ms (mean nss))
     :median-ms (ms (median nss))
     :min-ms (ms (apply min nss))
     :max-ms (ms (apply max nss))
     :last-result (:result (last samples))}))

(defn- print-row [{:keys [label iters mean-ms median-ms min-ms max-ms last-result]}]
  (println
   (str label
        "\titers=" iters
        "\tmedian=" (fmt median-ms) "ms"
        "\tmean=" (fmt mean-ms) "ms"
        "\tmin=" (fmt min-ms) "ms"
        "\tmax=" (fmt max-ms) "ms"
        "\tok=" (boolean (:ok last-result)))))

(defn- legacy-bisync-runtime-compound []
  (let [x (ids/new-node-id)
        y (ids/new-node-id)
        closure-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell x)
               (nb/install-cell y)
               (nb/install-cell closure-id bi-sync-closure bi-sync-closure))
        [compound n1] ((compound-propagator closure-id [x y] [x y]) n0)]
    {:net n1 :prop compound :x x :y y}))

(defn- child-bisync-net []
  (let [x (ids/new-node-id)
        y (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell x)
               (nb/install-cell y))
        [x-in n1] ((reality/p:reality-in :x-in x) n0)
        [y-in n2] ((reality/p:reality-in :y-in y) n1)
        [x->y n3] ((prop/id x y) n2)
        [y->x n4] ((prop/id y x) n3)
        [x-out n5] ((reality/p:reality-out :x-out x) n4)
        [y-out n6] ((reality/p:reality-out :y-out y) n5)]
    {:net n6 :x x :y y
     :props [x-in y-in x->y y->x x-out y-out]}))

(defn- lexical-bisync-compound []
  (let [{child :net child-x :x child-y :y} (child-bisync-net)
        child-id (ids/new-node-id)
        parent-x (ids/new-node-id)
        parent-y (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell child-id child child)
               (nb/install-cell parent-x)
               (nb/install-cell parent-y))
        [compound n1] ((lexical/p:lexical-compound
                        child-id
                        [[:x-in parent-x child-x]
                         [:y-in parent-y child-y]]
                        [[:x-out parent-x]
                         [:y-out parent-y]])
                       n0)]
    {:net n1 :prop compound :child-id child-id :x parent-x :y parent-y}))

(defn- child-nested-slot-net []
  (let [source (ids/new-node-id)
        right (ids/new-node-id)
        a (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell source)
               (nb/install-cell right)
               (nb/install-cell a))
        [source-in n1] ((reality/p:reality-in :source source) n0)
        [right-slot n2] ((obj/p:slot :right right source) n1)
        [a-slot n3] ((obj/p:slot :a a right) n2)
        [a-out n4] ((reality/p:reality-out :a a) n3)]
    {:net n4 :source source :a a
     :props [source-in right-slot a-slot a-out]}))

(defn- lexical-nested-accessor-compound []
  (let [{child :net source :source} (child-nested-slot-net)
        child-id (ids/new-node-id)
        parent-source (ids/new-node-id)
        parent-a (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell child-id child child)
               (nb/install-cell parent-source)
               (nb/install-cell parent-a))
        [compound n1] ((lexical/p:lexical-compound
                        child-id
                        [[:source parent-source source]]
                        [[:a parent-a]])
                       n0)]
    {:net n1 :prop compound :source parent-source :a parent-a}))

(defn- run-legacy-bisync []
  (let [{:keys [net prop x y]} (legacy-bisync-runtime-compound)
        n1 (-> net
               (nb/seed-cell x 10)
               (nb/run-propagators [prop]))]
    {:ok (and (= 10 (net/network-cell-value n1 x))
              (= 10 (net/network-cell-value n1 y)))}))

(defn- run-lexical-bisync []
  (let [{:keys [net prop x y]} (lexical-bisync-compound)
        n1 (-> net
               (nb/seed-cell x 10)
               (nb/run-propagators [prop]))]
    {:ok (and (= 10 (net/network-cell-value n1 x))
              (= 10 (net/network-cell-value n1 y)))}))

(defn- run-lexical-nested-accessor []
  (let [{:keys [net prop source a]} (lexical-nested-accessor-compound)
        n1 (-> net
               (nb/seed-cell source {:left [0 1] :right {:a 3}})
               (nb/run-propagators [prop]))]
    {:ok (= 3 (net/network-cell-value n1 a))}))

(defn -main [& _args]
  (println "kernel IO / lexical compound benchmark")
  (println "warmup=5 iters=25")
  (doseq [[label f] [["legacy-runtime-compound-bisync" run-legacy-bisync]
                     ["lexical-io-compound-bisync" run-lexical-bisync]
                     ["lexical-io-nested-accessor-read" run-lexical-nested-accessor]]]
    (print-row (bench-iters label 5 25 f))))
