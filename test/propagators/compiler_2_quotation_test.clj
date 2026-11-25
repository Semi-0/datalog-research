(ns propagators.compiler-2-quotation-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.runtime.session.program.source :as source]
            [tools.lain-example-runner :as r]))

(deftest quote-reader
  (is (= '[(quote x) (quote (+ x (quote y))) (quote (quote x))]
         (source/read-source-forms "'x '(+ x 'y) ''x")))
  (is (= '[(quote a) (quote (b))] (source/read-source-forms "' a ; skip\n '(b)")))
  (is (= [(symbol "x'") "don't" \' '(quote b)]
         (source/read-source-forms "x' \"don't\" \\' #_'a 'b")))
  (let [text (apply str (repeat 50000 "x'"))]
    (is (= text (parser/read-form (pr-str text)))))
  (is (= :quote (ast/type (parser/parse-string "'missing"))))
  (doseq [s ["'" "(quote)" "(quote a b)" "#=(+ 1 2)"]]
    (is (thrown? Exception (parser/parse-string s))))
  (is (false? (source/trace-form? "'(trace foo)")))
  (is (= "(quote (trace foo))" (source/normalize-trace-source "'(trace foo)"))))

(deftest rewrite-in-existing-session
  (let [s (r/new-session)]
    (r/load-source! s (slurp "examples/lain/tracer_gallery/13-code-as-data.lain"))
    (is (= 8 (r/project s 'original-answer)))
    (is (= 12 (r/project s 'rewritten-answer)))
    (r/load-source! s "(define head (car rewritten)) (define word 'never-bound)
                       (define local (let [units 11] (current-environment)))
                       (define shadowed (eval rewritten local))
                       (define looked-up (eval 'units local))
                       (define twice (eval ''never-bound scope))
                       (define literal (eval '42 scope))
                       (define empty (empty-list? '()))
                       (define nested (eval '(let [n (+ 1 2)] (* n units)) scope))")
    (doseq [[n expected] {'head '* 'word 'never-bound 'shadowed 22 'looked-up 11
                          'twice 'never-bound 'literal 42 'empty true 'nested 18}]
      (is (= expected (r/project s n)) (str n)))
    (is (nil? (r/binding-id s 'never-bound)))))

(deftest eval-waits-for-data-and-connects-live-bindings
  (let [s (r/new-session)]
    (r/load-source! s "(def-cells operand tail x)
                       (define scope (current-environment))
                       (define code (cons '+ (cons operand tail)))
                       (define answer (eval code scope))")
    (is (= :unresolved (r/project s 'answer)))
    (r/load-source! s "(-> 'x operand) (-> '(2) tail)")
    (is (= :unresolved (r/project s 'answer)))
    (r/load-source! s "(-> 9 x)")
    (is (= 11 (r/project s 'answer)))))

(deftest quotation-is-inert-even-when-constructors-are-shadowed
  (let [s (r/new-session)]
    (r/load-source! s "(define scope (current-environment))
                       (define q (let [list 15 cons 25] '(define hidden 3)))
                       (define operator (car q))
                       (define local-reader (network [x] (eval 'x (current-environment))))
                       (define local-value (local-reader 44))")
    (is (= 'define (r/project s 'operator)))
    (is (nil? (r/binding-id s 'hidden)))
    (is (= 44 (r/project s 'local-value)))))

(deftest eval-rejects-malformed-input
  (doseq [[source message] [["(eval 'x 17)" #"requires a live"]
                            ["(eval (cons '+ 2) (current-environment))" #"proper list"]
                            ["(eval 'x)" #"expression and an environment"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                          (r/load-source! (r/new-session) source)))))

(deftest eval-returns-a-lexically-captured-callable
  (let [s (r/new-session)]
    (r/load-source! s "(define x 50)
                       (define scope (let [x 4] (current-environment)))
                       (define f (eval '(network [y] (+ x y)) scope))
                       (define sum (f 8))")
    (is (= 12 (r/project s 'sum)))))

(deftest eval-waits-for-its-environment-and-propagates-conflicts
  (let [s (r/new-session)]
    (r/load-source! s "(def-cells scope operator)
                       (define form (cons operator '(4 3)))
                       (define answer (eval form scope))
                       (-> '+ operator)")
    (is (= :unresolved (r/project s 'answer)))
    (r/load-source! s "(-> (current-environment) scope)")
    (is (= 7 (r/project s 'answer)))
    (r/load-source! s "(-> '* operator)")
    (is (= :contradiction (r/project s 'answer)))))

(deftest eval-admits-a-definition-only-on-evaluation
  (let [s (r/new-session)]
    (r/load-source! s "(define scope (current-environment))
                       (define declaration '(define price 17))")
    (is (nil? (r/binding-id s 'price)))
    (r/load-source! s "(eval declaration scope)")
    (r/load-source! s "(define read-back (eval 'price scope))")
    (is (= 17 (r/project s 'read-back)))))
