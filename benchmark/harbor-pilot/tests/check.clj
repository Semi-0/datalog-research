(load-file "/app/src/pilot.clj")
(require '[clojure.test :refer [deftest is run-tests]])

(deftest stable-distinct-contract
  (is (= [] (pilot/stable-distinct-by identity [])))
  (is (= [1 2 3] (pilot/stable-distinct-by identity [1 2 1 3 2])))
  (is (= [nil false true] (pilot/stable-distinct-by identity [nil false nil true false])))
  (is (= [{:k 2 :v :a} {:k 1 :v :b}]
         (pilot/stable-distinct-by :k [{:k 2 :v :a} {:k 1 :v :b} {:k 2 :v :c}]))))

(let [{:keys [test pass fail error]} (run-tests)]
  (System/exit (if (and (= test 1) (= pass 4) (zero? fail) (zero? error)) 0 1)))
