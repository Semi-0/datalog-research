(require '[clojure.test :as t]
 'propagators.compiler-2.experimental.completed-when-pruning-test
 'propagators.network-facts.codec-test)
(def counters (ref t/*initial-report-counters*))
(def namespaces ['propagators.compiler-2.experimental.completed-when-pruning-test
                 'propagators.network-facts.codec-test])
(def vars (filter #(-> % meta :test) (mapcat #(vals (ns-publics %)) namespaces)))
(doseq [v (sort-by #(str (:name (meta %))) vars)]
 (let [start (System/nanoTime)
       f (future (binding [t/*report-counters* counters] (t/test-var v)))
       result (deref f 3000 :deadline)
       ms (/ (- (System/nanoTime) start) 1e6)]
  (println (:name (meta v)) ms)
  (when (= :deadline result) (future-cancel f) (throw (ex-info "Test deadline exceeded" {:test v})))))
(prn @counters)
(shutdown-agents)
(System/exit (if (zero? (+ (:fail @counters) (:error @counters))) 0 1))
