(ns propagators-chain-bench
  "Benchmark compound bi-sync chain propagation.
  Usage: clj -M:propagators-bench [chain-lens...]
  Default chain lengths: 10 100"
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :refer [cell-value-equal?]]
            [propagators.network :refer [compound-propagator]]
            [propagators.core :refer [run-tasks]]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net :refer [construct-cell]]
            [propagators.stdlib :refer [bi-sync-closure p:id]]))

;; --- network builders (same wiring as propagators-network-test) ---

(defn- install-compound [n closure-in-id closure-out-id inputs outputs]
  (let [[prop-id n'] ((compound-propagator closure-in-id closure-out-id inputs outputs) n)]
    [prop-id n']))

(defn build-chain [chain-len]
  (let [cells (vec (repeatedly chain-len new-node-id))
        closures-in (vec (repeatedly (dec chain-len) new-node-id))
        closures-out (vec (repeatedly (dec chain-len) new-node-id))
        cv bi-sync-closure
        ;; build the closure in cell into the network
        n (reduce (fn [net id] (second ((construct-cell id) net)))
                  net/empty-net
                  (into cells closures-in)) 
        ;; build the closures-out
        n1 (reduce (fn [net id] (second ((construct-cell id) net)))
                  n
                  (into cells closures-out))
        n2 (reduce #(net/assoc-net-cell %1 %2 (cell/cell cv cv)) n1 closures-in)
        [final-n props] (reduce
                   (fn [[n props] i]
                     (let [left (cells i)
                           right (cells (inc i))
                           k-i (closures-in i)
                           k-o (closures-out i)
                           [p n'] (install-compound n k-i k-o [left right] [left right])]
                       [n' (conj props p)]))
                   [n2 []]
                   (range (dec chain-len)))]
    {:net final-n :cells cells :props props}))

(defn build-chain-with-inject [chain-len inject-idx]
  (let [{:keys [net cells props]} (build-chain chain-len)
        mid (nth cells inject-idx)
        e (new-node-id)
        n (second ((construct-cell e) net))
        [e->mid n] ((p:id e mid) n)]
    {:net n :cells cells :props props :mid mid :e e :e->mid e->mid :inject-idx inject-idx}))

(defn- seed-cell [n cell-id v]
  (net/assoc-net-cell n cell-id (cell/cell v v)))

(defn- run-prop [n prop-id]
  (run-tasks (tq/enqueue tq/empty-queue prop-id) n))

(defn- run-compound-chain [n prop-ids]
  (reduce run-prop n prop-ids))

(defn- strongest [env cell-id]
  (cell/cell-strongest (net/env-get env cell-id)))

(defn- all-cells-have? [n cells expected]
  (every? #(cell-value-equal? expected (strongest (net/net-env n) %)) cells))

(defn- time-ns [thunk]
  (let [t0 (System/nanoTime)
        v (thunk)
        t1 (System/nanoTime)]
    {:result v :ns (- t1 t0)}))

(defn- mean [xs] (/ (reduce + 0 xs) (count xs)))

(defn- median [xs]
  (let [sorted (vec (sort xs))
        n (count sorted)]
    (nth sorted (quot n 2))))

(defn- bench-iters
  [label warmup iters thunk]
  (dotimes [_ warmup] (thunk))
  (let [samples (vec (repeatedly iters #(time-ns thunk)))]
    {:label label
     :iters iters
     :mean-ms (/ (mean (map :ns samples)) 1e6)
     :median-ms (/ (median (map :ns samples)) 1e6)
     :min-ms (/ (apply min (map :ns samples)) 1e6)
     :max-ms (/ (apply max (map :ns samples)) 1e6)
     :last-result (:result (last samples))}))

(defn- fmt-ms [x]
  (String/format java.util.Locale/US "%.3f" (to-array [(double x)])))

(defn- print-row [{:keys [label iters mean-ms median-ms min-ms max-ms]}]
  (println (str label
                "  iters=" iters
                "  mean=" (fmt-ms mean-ms) " ms"
                "  median=" (fmt-ms median-ms) " ms"
                "  min=" (fmt-ms min-ms) " ms"
                "  max=" (fmt-ms max-ms) " ms")))

(defn- default-bench-opts [chain-len]
  (cond
    (<= chain-len 100) {:warmup 3 :iters 20}
    (<= chain-len 1000) {:warmup 2 :iters 5}
    :else {:warmup 1 :iters 3}))

(defn- bench-one-propagation [label warmup iters thunk]
  (bench-iters label warmup iters thunk))

(defn- bench-head-cold-warm
  "First iter builds boundaries; later iters hit Strategy A cache on closure-out."
  [warmup iters net cells props seed-val]
  (let [samples (atom [])]
    (dotimes [_ warmup]
      (let [n (-> net (seed-cell (first cells) seed-val) (run-compound-chain props))]
        (swap! samples conj (:ns (time-ns (fn [] n))))))
    (dotimes [i iters]
      (let [timed (time-ns #(-> net
                                (seed-cell (first cells) seed-val)
                                (run-compound-chain props)))
            elapsed (:ns timed)]
        (swap! samples conj elapsed)
        (when (zero? i)
          (println "  first-iter (cold boundaries)")
          (println (str "    " (fmt-ms (/ elapsed 1e6)) " ms")))
        (when (= i (dec iters))
          (println "  last-iter (warm boundary cache)")
          (println (str "    " (fmt-ms (/ elapsed 1e6)) " ms")))))
    (let [xs (drop warmup @samples)
          cold (first xs)
          warm (last xs)]
      {:cold-ms (/ cold 1e6)
       :warm-ms (/ warm 1e6)
       :all-ms (map #(/ % 1e6) xs)})))

(defn bench-chain-len
  [chain-len opts]
  (let [{:keys [warmup iters seed-val skip-head?]
         :or {seed-val 42}}
        (merge (default-bench-opts chain-len) opts)
        build-t (time-ns #(build-chain chain-len))
        {:keys [net cells props]} (:result build-t)
        inject-idx (quot chain-len 2)
        inject-build-t (time-ns #(build-chain-with-inject chain-len inject-idx))
        inject-net (:result inject-build-t)
        expected seed-val
        head-cw (when-not skip-head?
                  (bench-head-cold-warm warmup iters net cells props seed-val))
        inject-bench (bench-one-propagation
                      (str "propagate-from-middle (inject c" inject-idx ", one run-prop)")
                      warmup iters
                      (fn []
                        (let [{:keys [net e e->mid]} inject-net]
                          (-> net
                              (seed-cell e seed-val)
                              (run-prop e->mid)))))
        inject-ok (all-cells-have? (:last-result inject-bench) (:cells inject-net) expected)]
    (println (str "\n=== chain-len " chain-len " (cells=" chain-len
                  ", compounds=" (dec chain-len)
                  ", Strategy A boundary cache) ==="))
    (println (str "build chain: " (fmt-ms (/ (:ns build-t) 1e6)) " ms"))
    (println (str "build chain+inject: " (fmt-ms (/ (:ns inject-build-t) 1e6)) " ms"))
    (when head-cw
      (println (str "head cold→warm: "
                    (fmt-ms (:cold-ms head-cw)) " → " (fmt-ms (:warm-ms head-cw)) " ms")))
    (print-row inject-bench)
    (println (str "middle inject propagation ok: " inject-ok))
    {:chain-len chain-len
     :head-cold-warm head-cw
     :inject inject-bench
     :inject-ok inject-ok}))

(defn -main [& args]
  (let [lens (if (seq args)
               (map #(Long/parseLong %) args)
               [10 100])]
    (println "propagators compound bi-sync chain benchmark")
    (println "Strategy A: reuse boundary avatars from closure-out when (f, ins, outs) match.")
    (println "Head reports first vs last iter; middle uses full bench-iters median.")
    (doseq [n lens]
      (bench-chain-len n (when (> n 5000) {:skip-head? true})))))
