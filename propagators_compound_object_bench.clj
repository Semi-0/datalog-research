(ns propagators-compound-object-bench
  "Benchmark compound-object slot guard on irrelevant slot wakes.
  Usage: clj -M:propagators-object-bench [iters]"
  (:require [propagators.datastructures.compound-object :as obj]
            [propagators.network-builder :as nb]
            [propagators.ids :refer [new-node-id]]))

(defn- mean [xs] (/ (reduce + 0 xs) (count xs)))

(defn- median [xs]
  (let [sorted (vec (sort xs))
        n (count sorted)]
    (nth sorted (quot n 2))))

(defn- time-ns [thunk]
  (let [t0 (System/nanoTime)
        v (thunk)
        t1 (System/nanoTime)]
    {:result v :ns (- t1 t0)}))

(defn- fmt-ms [x]
  (String/format java.util.Locale/US "%.3f" (to-array [(double x)])))

(defn- bench-iters [label warmup iters thunk]
  (dotimes [_ warmup] (thunk))
  (let [samples (vec (repeatedly iters #(time-ns thunk)))]
    {:label label
     :iters iters
     :mean-ms (/ (mean (map :ns samples)) 1e6)
     :median-ms (/ (median (map :ns samples)) 1e6)}))

(defn- print-row [{:keys [label iters mean-ms median-ms]}]
  (println (str label
                "  iters=" iters
                "  mean=" (fmt-ms mean-ms) " ms"
                "  median=" (fmt-ms median-ms) " ms")))

(defn- build-synced-cons []
  (let [head (new-node-id)
        tail (new-node-id)
        coll (new-node-id)
        n (nb/install-cells [head tail coll])
        [[car cdr] n] ((obj/p:cons head tail coll) n)]
    {:net (-> n
              (nb/seed-cell head 10)
              (nb/seed-cell tail 20)
              (nb/run-propagators [car cdr]))
     :car car}))

(defn -main [& args]
  (let [iters (if (seq args)
                (Long/parseLong (first args))
                200)
        needed (bench-iters "first car/cdr sync (subnet run)" 3 20
                            #(-> (build-synced-cons) :net))
        {:keys [net car]} (build-synced-cons)
        noop (bench-iters "guarded car no-op wake" 5 iters
                          #(nb/run-propagators net [car]))]
    (println "\n=== compound-object slot guard benchmark ===")
    (print-row needed)
    (print-row noop)
    (println (str "guarded no-op median is "
                  (fmt-ms (/ (:median-ms noop) (:median-ms needed)))
                  "x the first-sync median"))))
