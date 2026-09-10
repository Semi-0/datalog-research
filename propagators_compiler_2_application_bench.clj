(ns propagators-compiler-2-application-bench
  (:require [propagators.cells.cell :as cell]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.main :as main]
            [propagators.gur.accumulating.facts :as facts]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def warmup-count 10)
(def sample-count 30)

(defn- nested-body [depth expr]
  (if (zero? depth)
    expr
    (format "(let-cell [f]
               (<-> f (:: [y] (+ y 1)))
               (f %s))"
            (nested-body (dec depth) expr))))

(defn- nested-source [depth]
  (format "(let-cell [pipeline]
             (<-> pipeline (:: [x] %s))
             (pipeline 1))"
          (nested-body depth "x")))

(def cases
  [{:name :primitive
    :source "(+ 1 2)"}
   {:name :implicit-closure
    :source "((:: [x] (+ x 1)) 4)"}
   {:name :explicit-closure
    :source "(let-cell [out]
              ((network [x] [out] (+ x 1)) 4 out)
              out)"}
   {:name :nested-closures-1
    :source (nested-source 1)}
   {:name :nested-closures-3
    :source (nested-source 3)}
   {:name :nested-closures-5
    :source (nested-source 5)}])

(def strategies
  [{:name :direct-gur
    :opts {}}])

(defn- elapsed-nanos [f]
  (let [start (System/nanoTime)
        ret (f)]
    [(- (System/nanoTime) start) ret]))

(defn- median [xs]
  (let [sorted (vec (sort xs))
        n (count sorted)]
    (nth sorted (quot n 2))))

(defn- prop-count [network]
  (count (filter prop/prop? (vals (net/net-env network)))))

(defn- cell-count [network]
  (count (filter cell/cell? (vals (net/net-env network)))))

(defn- accumulated-frame-count
  [network]
  (reduce
   (fn [total id]
     (let [candidate (net/network-cell-strongest network id)]
       (cond
         (net/net? candidate)
         (+ total
            (count (net/network-dict-entry candidate facts/frame-index-key)))

         :else
         total)))
   0
   (keys (net/net-env network))))

(defn- run-case [{:keys [source]} {:keys [opts]}]
  (let [compiled (main/compile-source source (h/default-env) opts)
        final-network (nb/run-propagators (:net compiled) (:props compiled))]
    {:result (net/network-cell-strongest final-network (:cell compiled))
     :compiled-props (count (:props compiled))
     :cells (cell-count final-network)
     :props (prop-count final-network)
     :retained-applications (count (main/compiled-applications final-network))
     :gur-frames (accumulated-frame-count final-network)}))

(defn- sample-case [case strategy]
  (dotimes [_ warmup-count]
    (run-case case strategy))
  (let [samples (repeatedly sample-count
                            #(elapsed-nanos
                              (fn [] (run-case case strategy))))
        nanos (mapv first samples)
        last-result (second (last samples))]
    (assoc last-result
           :warmups warmup-count
           :samples sample-count
           :median-ms (/ (double (median nanos)) 1000000.0))))

(defn- benchmark []
  (vec
   (for [case cases
         strategy strategies]
     (assoc (sample-case case strategy)
            :case (:name case)
            :strategy (:name strategy)))))

(defn -main [& _args]
  (binding [*print-namespace-maps* false]
    (prn {:benchmark :compiler-2-application
          :warmups warmup-count
          :samples sample-count
          :results (benchmark)})))
