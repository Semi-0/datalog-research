(require '[clojure.test :refer [deftest is run-tests]]
         '[benchmark.stable-distinct :refer [stable-distinct-by]])
(deftest boundary-contract
  (is (= [] (stable-distinct-by identity nil)))
  (is (= [] (stable-distinct-by identity [])))
  (is (vector? (stable-distinct-by identity '(1 2 1))))
  (is (= [{:k 2 :v :a} {:k 1 :v :b}]
         (stable-distinct-by :k [{:k 2 :v :a} {:k 1 :v :b} {:k 2 :v :c}])))
  (is (= [nil false true] (stable-distinct-by identity [nil false nil true false])))
  (is (= [{:k nil :n 1} {:k false :n 2}]
         (stable-distinct-by :k [{:k nil :n 1} {:k false :n 2} {:k nil :n 3} {:k false :n 4}])))
  (is (= [[1 {:a #{2}}] [2]]
         (stable-distinct-by identity [[1 {:a #{2}}] [2] [1 {:a #{2}}]])))
  (is (= [1 2 3] (stable-distinct-by identity '(1 2 1 3 2))))
  (is (= [0 1 2 3 4] (stable-distinct-by #(mod % 5) (map identity (range 100))))))
(deftest evaluation-contract
  (let [calls (atom []) input [{:k nil} {:k false} {:k nil} {:k [1 2]} {:k [1 2]}]
        result (stable-distinct-by (fn [item] (swap! calls conj item) (:k item)) input)]
    (is (= input @calls))
    (is (= [{:k nil} {:k false} {:k [1 2]}] result))
    (is (= [{:k nil} {:k false} {:k nil} {:k [1 2]} {:k [1 2]}] input))))
(deftest scale-contract
  ;; 10K distinct keys, 20 repeats: catches linear scanning of prior keys.
  (let [input (vec (map #(mod % 10000) (range 200000)))
        started (System/nanoTime)
        result (stable-distinct-by identity input)
        elapsed (/ (- (System/nanoTime) started) 1e9)]
    (is (= (vec (range 10000)) result))
    (is (< elapsed 5.0) (str "200000 items took " elapsed "s"))))
(let [{:keys [fail error]} (run-tests 'user)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
