(ns propagators.prototype-2.compiler-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.prototype-2.compiler :as comp]
            [propagators.prototype-2.core :as p2]
            [propagators.prototype-2.p-list :as plist]))

(deftest compile-arithmetic
  (testing "(+ (* 2 3) 1) ⇒ 7"
    (let [[v] (comp/run '(+ (* 2 3) 1))]
      (is (= 7 v)))))

(deftest compile-define-and-use
  (testing "define binds a cell for later application"
    (let [[v] (comp/run '(define x 3) '(+ x 4))]
      (is (= 7 v)))))

(deftest compile-define-fn
  (testing "Church fn expands on apply"
    (let [[v] (comp/run '(define (add1 x) (+ x 1))
                         '(add1 10))]
      (is (= 11 v)))))

(deftest compile-cons-car-cdr
  (testing "lazy cons/car/cdr over Church p-list"
    (let [[v _ state] (comp/run '(define xs (cons 1 (cons 2 ())))
                                 '(car xs))
          network (:network state)]
      (is (= 1 v))
      (let [[v2] (comp/run '(define xs (cons 1 (cons 2 ())))
                            '(car (cdr xs)))]
        (is (= 2 v2)))
      (is (or (plist/p-list? network (:result state))
              (number? v))))))

(deftest compile-tail-reuses-out-cell
  (testing "tail application reuses program out — no extra result cell"
    (let [out :program-out
          state (comp/compile-program
                 (assoc (comp/fresh-state) :out out :tail? true)
                 '[(define (id x) x)
                   (id 42)])
          network (:network state)]
      (is (= out (:result state)))
      (let [network (p2/run-tasks network)]
        (is (= 42 (p2/cell-value network out)))))))

(deftest compile-if-delays-branch
  (testing "if compiles only the taken branch"
    (let [[v] (comp/run '(if (empty-list? ()) 1 2))]
      (is (= 1 v)))
    (let [[v] (comp/run '(if (empty-list? (cons 1 ())) 1 2))]
      (is (= 2 v)))))

(defn- p-list-values
  "Host vector of car values along a Church p-list handle in `network`."
  [network v]
  (cond
    (plist/empty-list? v) []
    (plist/church-cons? v)
    (into [(p2/cell-value network (:a v))]
          (p-list-values network (p2/cell-value network (:b v))))
    :else []))

(deftest compile-map-list
  (testing "Lain-shaped map-list via if + empty-list? + delayed else"
    (let [[v network]
          (comp/run
           '(define (inc x) (+ x 1))
           '(define (map-list f xs)
              (if (empty-list? xs)
                ()
                (cons (f (car xs)) (map-list f (cdr xs)))))
           '(map-list inc (cons 1 (cons 2 ()))))]
      (is (plist/church-cons? v))
      (is (= [2 3] (p-list-values network v))))))
