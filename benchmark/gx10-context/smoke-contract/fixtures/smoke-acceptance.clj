(require '[clojure.test :refer [deftest is run-tests]] '[benchmark.smoke :refer [double-number]])
(deftest arithmetic (is (= [0 6 -4] (mapv double-number [0 3 -2]))))
(let [{:keys [fail error]} (run-tests 'user)] (System/exit (if (zero? (+ fail error)) 0 1)))
