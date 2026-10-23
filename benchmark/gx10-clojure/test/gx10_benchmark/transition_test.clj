(ns gx10-benchmark.transition-test
  (:require [clojure.test :refer [deftest is]]
            [gx10-benchmark.session :as session]
            [gx10-benchmark.transition :as transition]))

(def clean {:resident-models [] :owned-processes [] :endpoint-up? false
            :physical-bytes (* 128 session/gib) :available-bytes (* 100 session/gib)})

(defn settle [samples baseline]
  (let [clock (atom 0) records (atom [])
        source (atom samples)]
    {:records records
     :run (fn []
            (transition/settle! #(let [v (first @source)]
                                   (when (next @source) (swap! source subvec 1)) v)
                                #(deref clock) #(swap! clock + %) #(swap! records conj %)
                                baseline {:deadline-ms 14000}))}))

(deftest all-sixteen-directed-transitions
  (doseq [previous [:flash :ornith :bf16 :qwen] next [:flash :ornith :bf16 :qwen]]
    (let [{:keys [run records]} (settle [(assoc clean :resident-models [previous])
                                        (assoc clean :owned-processes ["old-runner"])
                                        clean] nil)]
      (is (:verified? (run)) (str previous " -> " next))
      (is (= 13 (count @records))))))

(deftest acknowledgement-does-not-establish-unloaded
  (doseq [dirty [(assoc clean :resident-models [:old])
                  (assoc clean :owned-processes [123])
                  (assoc clean :endpoint-up? true)
                  (assoc clean :available-bytes (* 10 session/gib))]]
    (let [{:keys [run]} (settle [dirty] nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"settlement" (run))))))

(deftest baseline-shortfall-requires-investigation
  (let [{:keys [run]} (settle [clean] (* 104 session/gib))]
    (is (thrown? clojure.lang.ExceptionInfo (run)))))

(deftest delayed-ram-recovery-and-jitter
  (let [{:keys [run records]} (settle [(assoc clean :available-bytes (* 90 session/gib)) clean] (* 100 session/gib))]
    (is (:verified? (run)))
    (is (>= (:monotonic-ms (last @records)) 11000))))
