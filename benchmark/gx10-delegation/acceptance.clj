;; Host-owned independent evaluator; never copied into a worker checkout.
(require '[clojure.test :refer [deftest is run-tests]])
(require '[delegation.normalize :as n] '[delegation.project :as p]
         '[delegation.order :as o] '[delegation.batch :as b])
(defn error-data [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))
(deftest normalization
  (is (= [] (n/normalize-events nil)))
  (is (= [] (n/normalize-events [])))
  (is (= [[:put nil false] [:delete false] [:increment [:x] 2]]
         (n/normalize-events (list [:put nil false] [:delete false] [:increment [:x] 2]))))
  (doseq [bad [[:put :x] [:delete :x 1] [:increment :x 1.5] [:other] '(:put :x 1) nil]]
    (is (= {:type :invalid-event :index 1 :event bad}
           (error-data #(n/normalize-events [[:put :ok 1] bad]))))))
(deftest projection
  (is (= {} (p/project-events {} [])))
  (is (= {:x 4} (p/project-events {} [[:increment :x 4]]))))
(deftest projection-values
  (is (= {nil false} (p/project-events {} [[:put nil false]])))
  (is (= {} (p/project-events {:x 1} [[:delete :x] [:delete :absent]])))
  (is (= {:x 6} (p/project-events {:x 1} [[:increment :x 2] [:increment :x 3]])))
  (doseq [v [nil false "x" 1.5]]
    (is (= {:type :invalid-event :index 0 :event [:increment :x 1]}
           (error-data #(p/project-events {:x v} [[:increment :x 1]])))))
  (is (= :invalid-state (:type (error-data #(p/project-events [] []))))))
(deftest ordering
  (is (= [] (o/dependency-order [] [])))
  (is (= [:b :c :a :d] (o/dependency-order [:a :b :c :d] [[:c :a]]))))
(deftest ordering-cases
  (is (= [nil false [:x]] (o/dependency-order [nil false [:x]] [[nil false] [nil false]])))
  (is (= :unknown-node (:type (error-data #(o/dependency-order [:a] [[:a :missing]])))))
  (doseq [edges [[[:a :a]] [[:a :b] [:b :a]]]]
    (is (= :cycle (:type (error-data #(o/dependency-order [:a :b :c] edges))))))
  (let [nodes (vec (range 10000)) edges (mapv vector (range 9999) (range 1 10000))]
    (is (= nodes (o/dependency-order nodes edges)))))
(deftest composition
  (is (= {:order [] :state {:x 1}} (b/execute-batch {:x 1} [] [])))
  (is (= :invalid-state (:type (error-data #(b/execute-batch nil [] [])))))
  (let [jobs [{:id :a :events [[:increment :x 2]]}
              {:id :b :events [[:put :x 1]]}
              {:id :c :events [[:increment :x 3]]}
              {:id :d :events [[:put :done true]]}]]
    (is (= {:order [:b :c :a :d] :state {:x 6 :done true}}
           (b/execute-batch {} jobs [[:c :a]])))))
(deftest composition-errors
  (is (= :cycle (:type (error-data #(b/execute-batch {} [{:id :x :events []}] [[:x :x]])))))
  (is (= :invalid-event (:type (error-data #(b/execute-batch {} [{:id false :events [[:bad]]}] [])))))
  (is (= {:order [nil false] :state {nil 3}}
         (b/execute-batch {} [{:id nil :events [[:put nil 1]]}
                             {:id false :events [[:increment nil 2]]}] [[nil false]]))))
(let [result (run-tests 'user)]
  (System/exit (if (and (pos? (:pass result)) (zero? (+ (:fail result) (:error result)))) 0 1)))
