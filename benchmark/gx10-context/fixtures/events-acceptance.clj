(require '[clojure.test :refer [deftest is run-tests]]
         '[benchmark.event-projection :refer [project-events]])
(defn failure-data [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))
(deftest projection
 (is (= {} (project-events {} [])))
 (is (= {:a 2} (project-events {:a 2} nil)))
 (is (= {:a 3} (project-events {} [[:put :a 1] [:increment :a 2]])))
 (is (= {:a 4} (project-events {} [[:increment :a 4]])))
 (is (= {} (project-events {:a 1} [[:delete :a] [:delete :missing]])))
 (is (= {nil false false nil [1 2] {:a 3}}
        (project-events {} [[:put nil false] [:put false nil] [:put [1 2] {:a 3}]])))
 (is (= {:a -2} (project-events {:a 3} [[:increment :a -5]])))
 (is (= {:a 7} (project-events {:a 3} [[:delete :a] [:increment :a 7]])))
 (is (= {:a 2} (project-events {} (map identity [[:put :a 1] [:put :a 2]])))))
(deftest validation
 (is (= :invalid-state (:type (failure-data #(project-events nil [])))))
 (doseq [event [nil [] [:unknown :a] [:put :a] [:delete :a :extra]
                [:increment :a 1.5] [:increment :a] '(:put :a 1)]]
  (let [data (failure-data #(project-events {} [[:put :ok 1] event]))]
   (is (= :invalid-event (:type data)))
   (is (= 1 (:index data))) (is (= event (:event data)))))
 (doseq [value [nil false "2" 1.5]]
  (is (= :invalid-event (:type (failure-data #(project-events {:a value} [[:increment :a 1]])))))))
(deftest purity-and-scale
 (let [initial {:a 1} events [[:increment :a 2]]]
  (project-events initial events)
  (is (= {:a 1} initial)) (is (= [[:increment :a 2]] events)))
 (let [events (map #(vector :increment (mod % 100) 1) (range 100000))
       started (System/nanoTime) result (project-events {} events)]
  (is (= (zipmap (range 100) (repeat 1000)) result))
  (is (< (/ (- (System/nanoTime) started) 1e9) 5.0))))
(let [{:keys [fail error]} (run-tests 'user)] (System/exit (if (zero? (+ fail error)) 0 1)))
