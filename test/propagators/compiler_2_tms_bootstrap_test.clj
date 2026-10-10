(ns propagators.compiler-2-tms-bootstrap-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.main :as main]
            [propagators.compiler-2-functional-network-test :as fixture]
            [propagators.network :as net]))

(deftest active-bindings-exclude-deprecated-behavior
  (let [names (set (map first (main/tms-bindings)))]
    (is (contains? names 'premise-closure))
    (is (contains? names 'premise-retract))
    (is (contains? names '+))
    (doseq [name '[behavior behavior-cell behavior-event behavior-empty-state
                  behavior-add-event behavior-retain-last be:behavior
                  be:behavior-cell be:latest be:+ history latest]]
      (is (not (contains? names name)) (str name)))))

(deftest tms-entry-point-compiles-canonical-networks
  (let [compiled (main/compile-source-with-tms
                  "(let [] (define inc (network [x] (+ x 1))) (inc 41))")]
    (is (= 42 (net/network-cell-strongest
               (fixture/run compiled) (:cell compiled))))))

(deftest historical-entry-points-are-explicitly-deprecated
  (doseq [entry [#'main/behavior-tms-bindings
                 #'main/compile-expr-with-behavior-tms
                 #'main/compile-source-with-behavior-tms]]
    (is (true? (:deprecated (meta entry))))))
