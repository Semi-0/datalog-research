(require '[clojure.test :refer [deftest is run-tests]]
         '[benchmark.dependency-order :refer [dependency-order]])
(defn failure-type [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
(deftest ordering
 (is (= [] (dependency-order [] [])))
 (is (vector? (dependency-order [:a] [])))
 (is (= [:a :b :c] (dependency-order [:a :b :c] [])))
 (is (= [:b :c :a :d] (dependency-order [:a :b :c :d] [[:c :a]])))
 (is (= [:a :b :c :d] (dependency-order [:a :b :c :d] [[:a :c] [:b :c] [:c :d]])))
 (is (= [false nil :x] (dependency-order [nil false :x] [[false nil]])))
 (is (= [{:n 1} [2] :z] (dependency-order [{:n 1} [2] :z] [[{:n 1} [2]]])))
 (is (= [:a :b] (dependency-order [:a :b] [[:a :b] [:a :b]]))))
(deftest errors
 (is (= :unknown-node (failure-type #(dependency-order [:a] [[:a :b]]))))
 (is (= :unknown-node (failure-type #(dependency-order [:a] [[:b :a]]))))
 (is (= :cycle (failure-type #(dependency-order [:a] [[:a :a]]))))
 (is (= :cycle (failure-type #(dependency-order [:a :b] [[:a :b] [:b :a]]))))
 (is (= :cycle (failure-type #(dependency-order [:free :a :b] [[:a :b] [:b :a]])))))
(deftest preserved-input
 (let [nodes [:z :a :b] edges [[:a :b]]]
  (dependency-order nodes edges)
  (is (= [:z :a :b] nodes)) (is (= [[:a :b]] edges))))
(deftest scale
 (let [nodes (vec (range 10000)) edges (mapv vector (range 9999) (range 1 10000))
       started (System/nanoTime) result (dependency-order nodes edges)]
  (is (= nodes result)) (is (< (/ (- (System/nanoTime) started) 1e9) 8.0))))
(let [{:keys [fail error]} (run-tests 'user)] (System/exit (if (zero? (+ fail error)) 0 1)))
