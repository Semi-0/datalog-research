(ns propagators.prototype-2.observer-test
  "L-2 observer is independent of layers; layers+tell are an optional compose."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.prototype-2.compiler :as comp]))

(defn- run-obs
  "Observer prelude only."
  [& forms]
  (apply comp/run-with-prelude comp/l2-observer-prelude forms))

(defn- run-layers-tell
  "Layers + tell (no observer)."
  [& forms]
  (apply comp/run-with-prelude comp/l2-layers-tell-prelude forms))

(defn- run-obs+layers
  "Compose observer with layers+tell."
  [& forms]
  (apply comp/run-with-prelude
         (into (vec comp/l2-observer-prelude) comp/l2-layers-tell-prelude)
         forms))

(deftest observer-without-layers
  (testing "observer works on a plain value + display fn"
    (let [[v] (run-obs
               '(define (display v) (+ v 1))
               '(define obs (observer 10))
               '(obs display))]
      (is (= 11 v))))
  (testing "observer of a cell-bound value"
    (let [[v] (run-obs
               '(define (id x) x)
               '(define sensor 7)
               '(define obs (observer sensor))
               '(obs id))]
      (is (= 7 v)))))

(deftest tell-without-observer
  (testing "layers tell updates :base without any observer"
    (let [[v] (run-layers-tell
               '(define lv0 (layer-assoc () :base 1))
               '(define lv1 (tell lv0 99))
               '(layer-base lv1))]
      (is (= 99 v)))
    (let [[v] (run-layers-tell
               '(define lv0 (layer-assoc () :base 1))
               '(define lv1 (tell lv0 99))
               '(layer-base lv0))]
      (is (= 1 v)))))

(deftest compose-observer-with-layers-tell
  (testing "optional compose: observer pulls layer-base after tell"
    (let [[v] (run-obs+layers
               '(define lv0 (layer-assoc () :base 1))
               '(define lv1 (tell lv0 99))
               '(define obs (observer lv1))
               '(obs observe-base))]
      (is (= 99 v)))
    (let [[v] (run-obs+layers
               '(define lv0 (layer-assoc () :base 1))
               '(define lv1 (tell lv0 99))
               '(define obs0 (observer lv0))
               '(obs0 observe-base))]
      (is (= 1 v)))))
