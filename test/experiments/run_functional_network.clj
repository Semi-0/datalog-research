;; Run sequentially: namespace/fixture startup is separate from each test var.
(require '[clojure.test :as test])
(def namespaces
  (if (seq *command-line-args*)
    (mapv symbol *command-line-args*)
    '[experiments.functional-network-test
      experiments.functional-network-effects-test
      experiments.functional-network-compiler-test
      experiments.functional-network-contract-test
      experiments.functional-network-gur-test
      experiments.functional-network-expressiveness-test]))
(def startup-start (System/nanoTime))
(doseq [namespace namespaces] (require namespace))
(println :namespace-and-fixture-startup-ms (/ (- (System/nanoTime) startup-start) 1e6))

(def counters (ref test/*initial-report-counters*))
(doseq [namespace namespaces
        [name test-var] (sort-by key (ns-publics namespace))
        :when (:test (meta test-var))]
  (let [started (System/nanoTime)
        task (future (binding [test/*report-counters* counters] (test/test-var test-var)))
        result (deref task 3000 ::timeout)
        elapsed (/ (- (System/nanoTime) started) 1e6)]
    (println (symbol (str namespace) (str name)) elapsed :ms)
    (when (or (= ::timeout result) (> elapsed 3000))
      (future-cancel task)
      (println :failed :individual-test-exceeded-three-seconds)
      (shutdown-agents)
      (System/exit 2))))
(println @counters)
(shutdown-agents)
(System/exit (if (zero? (+ (:fail @counters) (:error @counters))) 0 1))
