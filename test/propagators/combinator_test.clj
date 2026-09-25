(ns propagators.combinator-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.combinator :as combinator]))

(deftest branch-validates-its-clause-list
  (doseq [clauses [[] [odd? inc]]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"predicate/handler pairs"
         (apply combinator/branch clauses)))))

(deftest branch-selects-the-first-match-and-forwards-inputs
  (let [calls (atom [])
        dispatch
        (combinator/branch
         #(= :number %)
         (fn [_kind value] (swap! calls conj :number) (* 2 value))
         #(= :fallback-kind %)
         (fn [_kind value] (swap! calls conj :second) value)
         (fn [_kind value] (swap! calls conj :fallback) value))]
    (is (= 8 (dispatch :number 4)))
    (is (= 3 (dispatch :unknown 3)))
    (is (= [:number :fallback] @calls))))
