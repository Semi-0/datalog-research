(ns propagators.prototype-2.world-test
  "World algebra experiment: env spines as worlds, revive forks, observe under env."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [propagators.prototype-2.compiler :as comp]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.world :as world]
            [propagators.prototype-2.world-specs :as specs]))

(deftest quote-compiles-to-host-data
  (testing "L-2 quote yields symbols/lists as cell content"
    (let [[v] (comp/run '(quote a))]
      (is (= 'a v)))
    (let [[v] (comp/run '(quote (replace-definition a 100)))]
      (is (= '(replace-definition a 100) v)))))

(deftest revive-replace-definition-observe
  (testing "W0/W1: mono-directional fork; observe expression under each world"
    (let [w0 (world/world-from-forms '(define a 10))
          w1 (world/revive w0 '(replace-definition a 100))
          [v0] (world/observe w0 '(+ (* a 2) 2))
          [v1] (world/observe w1 '(+ (* a 2) 2))]
      (is (= 22 v0))
      (is (= 202 v1))
      ;; parent world unchanged
      (let [[v0'] (world/observe w0 '(+ (* a 2) 2))]
        (is (= 22 v0')))
      (is (= (:world-id w0) (:parent-id w1))))))

(deftest observe-bound-symbol
  (testing "observe of a binding name"
    (let [w0 (world/world-from-forms '(define c 22))
          [v] (world/observe w0 'c)]
      (is (= 22 v))))
  (testing "world-relative apply: (c) under W1 sees revive shadow"
    (let [w0 (world/world-from-forms
              '(define a 10)
              '(define (c) (+ (* a 2) 2)))
          w1 (world/revive w0 '(replace-definition a 100))
          [v0] (world/observe w0 '(c))
          [v1] (world/observe w1 '(c))
          [a1] (world/observe w1 'a)]
      (is (= 22 v0))
      (is (= 202 v1) "call-site world env shadows free a")
      (is (= 100 a1)))))

(deftest world-list-spine-inspectable
  (testing "world handle is (cons id (cons env-ref lineage)); parent env unchanged"
    (let [w0 (world/world-from-forms '(define a 10))
          w1 (world/revive w0 '(replace-definition a 100))
          n0 (:network w0)
          n1 (:network w1)
          h0 (:handle w0)
          h1 (:handle w1)
          outer0 (net/cell-value n0 h0)
          outer1 (net/cell-value n1 h1)]
      (is (plist/church-cons? outer0))
      (is (plist/church-cons? outer1))
      (is (= (:world-id w0) (world/world-id-of n0 h0)))
      (is (= (:world-id w1) (world/world-id-of n1 h1)))
      (is (= (:env-cell w0) (world/world-env-of n0 h0)))
      (is (= (:env-cell w1) (world/world-env-of n1 h1)))
      (is (not= (world/world-env-of n0 h0) (world/world-env-of n1 h1)))
      (is (s/valid? ::specs/world w0))
      (is (s/valid? ::specs/world w1))
      (let [[v0] (world/observe w0 'a)]
        (is (= 10 v0) "W0 env untouched by W1 revive")))))

(deftest curry-then-revive-sees-shadow
  (testing "(define g (f 1)); revive shadow; (g 2) under W1 uses call-site env + :bound"
    (let [w0 (world/world-from-forms
              '(define a 10)
              '(define (f x y) (+ (* a x) y))
              '(define g (f 1)))
          w1 (world/revive w0 '(replace-definition a 100))
          [v0] (world/observe w0 '(g 2))
          [v1] (world/observe w1 '(g 2))]
      (is (= 12 v0) "W0: 10*1+2")
      (is (= 102 v1) "W1: 100*1+2 via bound x + world a"))))

(deftest rewrite-via-quoted-rule
  (testing "revive accepts quoted replace-definition data"
    (let [[rule] (comp/run '(quote (replace-definition a 100)))
          w0 (world/world-from-forms '(define a 1))
          w1 (world/revive w0 rule)
          [v] (world/observe w1 'a)]
      (is (= 100 v)))))

(deftest reify-retire-activate
  (testing "hibernate WorldSpec and activate back"
    (let [w0 (world/world-from-forms '(define a 10))
          w1 (world/revive w0 '(replace-definition a 100))
          spec (world/reify-world w1)
          _ (is (s/valid? ::specs/world-id (:world-id spec)))
          effect (world/retire w1)
          woken (world/activate (:world-id effect))
          [v] (world/observe woken 'a)]
      (is (= :retire (:effect effect)))
      (is (= 100 v)))))

(deftest lineage-is-linked-list-shaped
  (testing "each revive conses a frame onto lineage"
    (let [w0 (world/world-from-forms '(define a 1))
          w1 (world/revive w0 '(replace-definition a 2))
          w2 (world/revive w1 '(replace-definition a 3))]
      (is (= 2 (count (:lineage w2))))
      (is (= 1 (count (:lineage w1))))
      (is (empty? (:lineage w0)))
      (is (= (:world-id w1) (:parent-id w2)))
      (is (= 2 (count (world/lineage-frames-of (:network w2) (:handle w2))))))))

(deftest l2-current-world-revive-observe
  (testing "spawn and observe entirely in L-2"
    (let [[v] (comp/run
               '(define a 10)
               '(define (c) (+ (* a 2) 2))
               '(define w0 (current-world))
               '(define w1 (revive w0 (quote (replace-definition a 100))))
               '(observe w1 (c)))]
      (is (= 202 v))))
  (testing "observe expression under child; parent still 22 via host observe"
    (let [[_ network state]
          (comp/run
           '(define a 10)
           '(define (c) (+ (* a 2) 2))
           '(define w0 (current-world))
           '(define w1 (revive w0 (quote (replace-definition a 100))))
           'w0)
          w0-cell (:result state)
          w0 (world/world-from-handle network w0-cell)
          [v0] (world/observe w0 '(c))]
      (is (= 22 v0)))))
