(ns propagators.bool4-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.bool4 :as bool4]))

(deftest bool4-not-truth-table
  (testing "not"
    (is (= bool4/nothing (bool4/not bool4/nothing)))
    (is (= false (bool4/not true)))
    (is (= true (bool4/not false)))
    (is (= bool4/contradiction (bool4/not bool4/contradiction)))))

(deftest bool4-and-truth-table
  (testing "and"
    (is (= true (bool4/and true true)))
    (is (= false (bool4/and true false)))
    (is (= bool4/nothing (bool4/and true bool4/nothing)))
    (is (= bool4/contradiction (bool4/and true bool4/contradiction)))
    (is (= false (bool4/and false bool4/contradiction)))
    (is (= false (bool4/and bool4/nothing bool4/contradiction)))))

(deftest bool4-or-truth-table
  (testing "or"
    (is (= false (bool4/or false false)))
    (is (= true (bool4/or true false)))
    (is (= bool4/nothing (bool4/or false bool4/nothing)))
    (is (= bool4/contradiction (bool4/or false bool4/contradiction)))
    (is (= true (bool4/or true bool4/contradiction)))
    (is (= true (bool4/or bool4/nothing bool4/contradiction)))))

(deftest bool4-implies-truth-table
  (testing "implies"
    (is (= false (bool4/implies true false)))
    (is (= true (bool4/implies false true)))
    (is (= bool4/nothing (bool4/implies bool4/nothing false)))
    (is (= bool4/nothing (bool4/implies true bool4/nothing)))
    (is (= bool4/contradiction (bool4/implies bool4/contradiction false)))))

(deftest bool4-join-truth-table
  (testing "knowledge-lattice join"
    (is (= bool4/nothing (bool4/join bool4/nothing bool4/nothing)))
    (is (= true (bool4/join bool4/nothing true)))
    (is (= false (bool4/join bool4/nothing false)))
    (is (= bool4/contradiction (bool4/join bool4/nothing bool4/contradiction)))
    (is (= bool4/contradiction (bool4/join true false)))
    (is (= bool4/contradiction (bool4/join true bool4/contradiction)))
    (is (= bool4/contradiction (bool4/join false bool4/contradiction)))))

(deftest bool4-meet-truth-table
  (testing "knowledge-lattice meet"
    (is (= bool4/nothing (bool4/meet bool4/nothing bool4/nothing)))
    (is (= bool4/nothing (bool4/meet bool4/nothing true)))
    (is (= bool4/nothing (bool4/meet bool4/nothing false)))
    (is (= bool4/nothing (bool4/meet bool4/nothing bool4/contradiction)))
    (is (= bool4/nothing (bool4/meet true false)))
    (is (= true (bool4/meet true bool4/contradiction)))
    (is (= false (bool4/meet false bool4/contradiction)))))
