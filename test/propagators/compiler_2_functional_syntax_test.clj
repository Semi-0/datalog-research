(ns propagators.compiler-2-functional-syntax-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.compiler.declarations :as declarations]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.application-ports :as ports]
            [propagators.compiler-2-functional-network-test :as fixture]
            [propagators.cells.value :as value]
            [propagators.gur :as gur]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn evaluate [source]
  (let [compiled (compiler/compile-source source)]
    (net/network-cell-strongest (fixture/run compiled) (:cell compiled))))

(deftest all-handler-results-are-cells
  (doseq [source ["nil" "42" "+" "missing" "(define missing)"
                  "(define answer 42)" "(network () 42)" "(+ 2 3)"
                  "(let [x 42] x)" "(let-cell [x] x)" "(when missing 42)"]]
    (is (ids/node-id? (:cell (compiler/compile-source source))))))

(deftest literal-nil-is-not-an-omitted-definition
  (is (= :literal (ast/type (ast/body (compiler/parse-form '(define x nil))))))
  (is (nil? (ast/body (compiler/parse-form '(define x)))))
  (is (nil? (evaluate "(let [] (define x nil) x)")))
  (is (value/nothing? (evaluate "(let [] (define x) x)"))))

(deftest define-refines-the-existing-cell
  (is (= 42 (evaluate "(let-cell [x] (define x 42) x)")))
  (is (= 42 (evaluate "(let [] (define x) (define x 42) x)")))
  (is (value/contradiction? (evaluate "(let [] (define x 1) (define x 2) x)"))))

(deftest let-supplies-explicit-shadowing
  (let [compiled (compiler/compile-source
                  "(let [x 1] (list (let [x 2] x) x))")]
    (is (= [2 1] (fixture/values (fixture/run compiled) (:cell compiled))))))

(deftest compiler-operands-alias-retains-its-visible-declaration
  (let [compiled (compiler/compile-source
                  "(let [] (define make-list list) (make-list 1 2))")
        network (fixture/run compiled)]
    (is (= [1 2] (fixture/values network (:cell compiled))))
    (is (seq (get (net/network-dict-entry network gur/name-bindings-key)
                  declarations/declaration-link-key)))))

(deftest named-primitive-is-first-class
  (is (= 5 (evaluate "(let [] (define add +) (add 2 3))")))
  (is (= 5 (evaluate "(apply + (list 2 3))"))))

(deftest list-and-vector-formals-agree
  (is (= 42 (evaluate "((network (x) (+ x 1)) 41)")))
  (is (= 42 (evaluate "((network [x] (+ x 1)) 41)"))))

(deftest retained-rest-parameters-use-the-body-return
  (is (= 2 (evaluate "((network (x & rest) (car rest)) 1 2 3)")))
  (is (= :compiler-2/list-empty (evaluate "((network (& rest) rest))"))))

(deftest removed-source-forms-are-errors
  (doseq [source ["(def x 1)" "(def-cell x)" "(def-cells x y)"
                  "(def-net f [x] [y] x)" "(def-constraint f [x] x)"
                  "(compound [x] y x)" "(cell-expr [x] x)" "(:: [x] x)"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"removed"
                         (compiler/compile-source source)))))

(deftest old-explicit-output-vectors-are-errors
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"output vectors were removed"
                       (compiler/compile-source "(network [x] [y] (-> x y))"))))

(deftest inspect-scalar-empty-and-member-ports
  (doseq [[source expected-count] [["((network () 42))" 0]
                          ["((network () (list)))" 0]
                          ["((network () (list 1 2)))" 2]]]
    (let [compiled (compiler/compile-source source)
          network (fixture/run compiled)
          topology (application/application-topology-for-result network (:cell compiled))
          inspected (ports/application-ports network topology)]
      (is (= :complete (:output-status inspected)))
      (is (= expected-count (count (:member-outputs inspected))))
      (is (= (:cell compiled) (:return-id inspected))))))

(deftest inspect-unknown-return-as-pending
  (let [compiled (compiler/compile-source "(let-cell [x] ((network () x)))")
        network (fixture/run compiled)
        topology (application/application-topology-for-result network (:cell compiled))]
    (is (= :pending (:output-status (ports/application-ports network topology))))))
