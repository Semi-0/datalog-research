(ns propagators.compiler-2-functional-network-compiler-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.runtime.returned-outputs :as experiment]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.runtime.linked-application :as linked]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2-functional-network-test :as fixture]
            [propagators.cells.value :as value]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.gur :as gur]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn run [compiled]
  (nb/run-propagators (:net compiled) (:props compiled)))

(defn result [compiled]
  (net/network-cell-strongest (run compiled) (:cell compiled)))

(deftest literals-and-primitive-symbols-return-cells
  (doseq [form [42 false "answer" :answer]]
    (let [compiled (compiler/compile-form form)]
      (is (ids/node-id? (:cell compiled)))
      (is (= form (result compiled)))))
  (doseq [symbol ['+ 'list 'apply]]
    (let [compiled (compiler/compile-form symbol)]
      (is (ids/node-id? (:cell compiled)))
      (is (gur/recursive-closure? (result compiled))))))

(deftest entry-points-share-parser-and-cps-semantics
  (let [source "((network (x) (+ x 1)) 4)"
        expression (compiler/parse-source source)
        compiled [(compiler/compile-source source)
                  (compiler/compile-form '((network (x) (+ x 1)) 4))
                  (compiler/compile-expr expression)]]
    (is (= [5 5 5] (mapv result compiled)))
    (is (every? #(ids/node-id? (:cell %)) compiled))
    (is (= 1 (count (set (map :cell compiled)))))))

(deftest definition-expression-returns-a-receipt-cell
  (let [compiled (compiler/compile-source "(define answer 42)")
        network (run compiled)
        receipt (net/network-cell-strongest network (:cell compiled))]
    (is (ids/node-id? (:cell compiled)))
    (is (= :binding (:declaration/kind receipt)))
    (is (= 42 (net/network-cell-strongest network (:binding/target receipt))))))

(deftest ordered-program-definitions-feed-subsequent-expressions
  (let [compiled (compiler/compile-program
                  '[(define increment (network (x) (+ x 1)))
                    (define answer (increment 41))
                    answer])]
    (is (= 42 (result compiled)))))

(deftest network-declaration-does-not-evaluate-body
  (let [compiled (compiler/compile-source "(network (x) (+ x 1))")
        receipt (result compiled)]
    (is (gur/recursive-closure? receipt))
    (is (= :body-return (:compiler-2/output-interface receipt)))
    (is (empty? (experiment/outputs (run compiled))))))

(deftest lexical-capture-and-shadowing-use-shared-compiler
  (is (= 12 (result (compiler/compile-source
                    "(let [bias 2]
                       (define add-bias (network (x) (+ x bias)))
                       (let [bias 100] (add-bias 10)))")))))

(deftest higher-order-application-uses-runtime-list
  (is (= 42 (result (compiler/compile-source
                    "(let []
                       (define make (network (bias) (network (x) (+ bias x))))
                       (define add-two (make 2))
                       (apply add-two (list 40)))")))))

(deftest primitive-operator-is-a-first-class-cell-value
  (let [compiled (compiler/compile-source
                  "(let [] (define build-list list)
                     (apply build-list (list 2 3)))")]
    (is (= [2 3] (fixture/values (run compiled) (:cell compiled))))))

(deftest conditionals-use-shared-parser-and-primitive
  (is (= 42 (result (compiler/compile-source
                    "(let [x 40] (if (> x 0) (+ x 2) 0))")))))

(deftest availability-guard-declares-topology-after-late-input
  (let [compiled (compiler/compile-source
                  "(let-cell [trigger answer]
                     (when trigger (define answer 42)) (list trigger answer))")
        initial (run compiled)
        [trigger answer] (fixture/list-ids initial (:cell compiled))
        seeded (nb/seed-cell initial trigger true)
        activated (nb/run-propagators seeded (nb/neighbor-propagator-ids seeded trigger))]
    (is (value/nothing? (net/network-cell-strongest initial answer)))
    (is (= 42 (net/network-cell-strongest activated answer)))))

(deftest unknown-symbol-is-a-waiting-result-cell
  (let [compiled (compiler/compile-source "not-yet-defined")]
    (is (ids/node-id? (:cell compiled)))
    (is (value/nothing? (result compiled)))))

(deftest runtime-application-is-unavailable-until-propagation
  (let [compiled (compiler/compile-source "((network (x) (+ x 1)) 4)")]
    (is (value/nothing? (net/network-cell-strongest (:net compiled) (:cell compiled))))
    (is (= 5 (result compiled)))))

(deftest equivalent-compilation-reuses-deterministic-identities
  (let [a (compiler/compile-source "((network (x) (+ x 1)) 4)")
        b (compiler/compile-source "((network (x) (+ x 1)) 4)")]
    (is (= (:cell a) (:cell b)))
    (is (= (set (:props a)) (set (:props b))))))

(deftest explicit-output-and-constraint-special-forms-are-not-core
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"was removed"
                       (compiler/compile-form '(def-constraint add [a b c] (-> (+ a b) c)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"derive outputs"
                       (compiler/compile-expr
                        (ast/network '[x] 'out (ast/sym 'out))))))

(deftest malformed-program-and-source-have-explicit-errors
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at least one expression"
                       (compiler/compile-program [])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one form"
                       (compiler/compile-source "42 43"))))
