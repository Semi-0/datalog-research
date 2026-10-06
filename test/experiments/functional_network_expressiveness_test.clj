(ns experiments.functional-network-expressiveness-test
  "Bounded observational comparisons, not a proof of language expressiveness."
  (:require [clojure.test :refer [deftest is testing]]
            [experiments.functional-network :as experiment]
            [experiments.functional-network.compiler :as language]
            [experiments.functional-network-test :as fixture]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.network :as net]))

(defn baseline [source]
  (compiler/compile-expr-with-bindings
   (parser/parse-form (parser/read-form source))
   (basis/default-bindings)
   {:seed [:experiment/expressiveness-baseline source]}))

(defn result-value [compiled]
  (net/network-cell-strongest (fixture/run compiled) (:cell compiled)))

(def old-addition
  "(def-constraint constrain-+ [a b c]
      (-> (+ a b) c)
      (-> (- c b) a)
      (-> (- c a) b))")

(deftest fixed-arity-constraint-observations-agree
  (doseq [seeds ["(-> 2 a) (-> 3 b)"
                 "(-> 3 b) (-> 5 c)"
                 "(-> 2 a) (-> 5 c)"]]
    (testing seeds
      (let [old-source (str "(let-cell [a b c] " old-addition " " seeds
                            " (constrain-+ a b c) (list a b c))")
            old (baseline old-source)
            new (language/compile-source (fixture/source seeds))]
        (is (= [2 3 5] (fixture/values (fixture/run old) (:cell old))))
        (is (= [2 3 5] (fixture/values (fixture/run new) (:cell new))))))))

(deftest naive-constraint-replacement-changes-its-result
  (let [old (baseline (str "(let-cell [a b c] " old-addition
                           " (-> 2 a) (-> 3 b) (constrain-+ a b c))"))
        new (language/compile-source
             (str "(let-cell [a b c] " fixture/addition-definition
                  " (-> 2 a) (-> 3 b) (constrain-+ a b c))"))]
    (is (= 5 (result-value old)))
    (is (= [2 3 5] (fixture/values (fixture/run new) (:cell new))))))

(deftest definition-receipt-is-an-observable-semantic-change
  (is (= 42 (result-value (baseline "(def answer 42)"))))
  (let [receipt (result-value (language/compile-source "(define answer 42)"))]
    (is (map? receipt))
    (is (not= 42 receipt))))

(deftest scalar-closure-application-observations-agree
  (is (= 5 (result-value (baseline "((cell-expr [a b] (+ a b)) 2 3)"))))
  (is (= 5 (result-value
            (language/compile-source "((network (a b) (+ a b)) 2 3)")))))
