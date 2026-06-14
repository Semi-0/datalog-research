(ns differential-dataflow.frontier-test
  (:require [clojure.test :refer [deftest is]]
            [differential-dataflow.frontier :as f]))

(deftest frontier-antichain
  (is (f/frontier? #{[0 1] [1 0]}))
  (is (f/frontier? #{[2 5] [4 1]}))
  (is (f/frontier? #{}))
  (is (f/frontier? #{[0 0]}))
  (is (not (f/frontier? #{[0 0] [0 1]})))
  (is (not (f/frontier? #{[0 0] [1 0] [0 1]}))))

(deftest frontier-merge-cases
  (is (= #{[2 5] [4 1]}
         (f/frontier-merge #{[2 5]} [4 1])))
  (is (= #{[3 6]}
         (f/frontier-merge #{[2 5]} [3 6])))
  (is (= #{[0 1] [2 0]}
         (f/frontier-merge #{[0 1] [1 0]} [2 0])))
  (is (= #{[0 1] [1 0]}
         (f/frontier-merge #{[0 1] [1 0]} [1 0])))
  (is (= #{[3 6] [4 1]}
         (f/frontier-merge #{[2 5] [4 1]} #{[3 6]})))
  (is (= #{[0 1] [1 0]}
         (f/frontier-merge #{[0 1] [1 0]} :not-a-version)))
  (is (= #{[2 5] [4 1]}
         (f/frontier-merge #{[2 5] [4 1]} #{[0 0]}))))

(deftest frontier-advanced-by
  (is (f/frontier-advanced-by? #{[2 5] [4 1]} #{[3 6] [4 1]}))
  (is (f/frontier-advanced-by? #{[0 1] [1 0]} #{[0 1] [1 0]}))
  (is (not (f/frontier-advanced-by? #{[2 5]} #{[4 1]})))
  (is (not (f/frontier-advanced-by? #{[2 5] [4 1]} #{[0 0]}))))

(deftest frontier-algebra-supports-scalars-and-product-versions
  (is (f/version-lte? 1 2))
  (is (not (f/version-lte? 2 1)))
  (is (= 3 (f/version-lub 1 3)))
  (is (= #{3} (f/frontier-meet #{1} #{3})))
  (is (f/version-lte? [1 2] [1 4]))
  (is (not (f/version-lte? [2 1] [1 2])))
  (is (= [2 4] (f/version-lub [1 4] [2 3])))
  (is (= #{[1 2] [2 1]} (f/frontier [[1 2] [2 1] [2 2]]))))

(deftest version-shaping-supports-iteration-scopes
  (is (= [0 0] (f/version-extend 0)))
  (is (= [0 1 0] (f/version-extend [0 1])))
  (is (= 0 (f/version-truncate [0 3])))
  (is (= [0 1] (f/version-truncate [0 1 3])))
  (is (= [0 4] (f/version-apply-step [0 1] 3)))
  (is (= #{[1 0]} (f/frontier-extend #{1})))
  (is (= #{1} (f/frontier-truncate #{[1 2]})))
  (is (= #{[1 3]} (f/frontier-apply-step #{[1 1]} 2))))
