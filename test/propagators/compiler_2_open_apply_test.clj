(ns propagators.compiler-2-open-apply-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2-functional-network-test :as functional]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2-supported-compound-if-test :as acceptance]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as object]
            [propagators.gur :as gur]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]))

(defn- evaluate [source]
  (functional/run (compiler/compile-source source)))

(defn- bounded-cycle-check [f]
  (let [steps (atom 0)
        deadline (+ (System/nanoTime) 20000000000)]
    (binding [runner/*advance-transform*
              (runner/compose-advance-transforms
               runner/*advance-transform*
               (fn [advance]
                 (fn [state continuations]
                   (when (or (> (swap! steps inc) 2000)
                             (> (System/nanoTime) deadline))
                     (throw (ex-info "Cyclic apply exceeded propagation budget" {:steps @steps})))
                   (advance state continuations))))]
      (f))))

(deftest cyclic-argument-lists-are-rejected-through-plain-and-supported-owners
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cyclic input interfaces"
                       (bounded-cycle-check
                        #(evaluate "(let-cell [cycle]
                                      (-> (cons 1 cycle) cycle)
                                      (apply + cycle))"))))
  (doseq [ingress [:publication :membership]]
    (testing ingress
      (let [loaded (acceptance/load-session
                    "(def-cells flag cycle)
                     (-> (cons 1 cycle) cycle)
                     (define selected (if flag cycle cycle))
                     (define answer (apply + selected))")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cyclic input interfaces"
                             (bounded-cycle-check
                              #(acceptance/publish! loaded ingress 'flag 1 true))))))))

(deftest fixed-apply-does-not-wait-for-an-open-tail
  (let [compiled (compiler/compile-source
                  "(let-cell [a b tail]
                     (list a b tail
                       (apply (network [x y] (+ x y))
                              (cons a (cons b tail)))))")
        initial (functional/run compiled)
        [a b _ answer] (functional/list-ids initial (:cell compiled))
        seeded (-> initial (nb/seed-cell a 2) (nb/seed-cell b 3))
        result (nb/run-propagators seeded
                                  (concat (nb/neighbor-propagator-ids seeded a)
                                          (nb/neighbor-propagator-ids seeded b)))
        repeated (nb/run-propagators result (:props compiled))]
    (is (value/nothing? (net/network-cell-strongest initial answer)))
    (is (= 5 (net/network-cell-strongest result answer)))
    (is (= (net/net-graph result) (net/net-graph repeated)))
    (is (= (set (keys (net/net-env result)))
           (set (keys (net/net-env repeated)))))))

(deftest zero-argument-apply-can-use-an-unresolved-list-cell
  (let [compiled (compiler/compile-source
                  "(let-cell [tail] (apply (network [] 42) tail))")
        result (functional/run compiled)]
    (is (= 42 (net/network-cell-strongest result (:cell compiled))))))

(deftest variadic-apply-keeps-the-live-remainder-tail
  (let [compiled (compiler/compile-source
                  "(let-cell [tail]
                     (list tail
                       (apply (network [x & xs] xs) (cons 1 tail))))")
        initial (functional/run compiled)
        [tail answer] (functional/list-ids initial (:cell compiled))
        [head end] (mapv gur/stable-node-id [[::head] [::end]])
        prepared (reduce nb/ensure-cell initial [head end])
        [props declared] ((object/p:cons head end tail) prepared)
        seeded (-> declared (nb/seed-cell head 9)
                   (nb/seed-cell end basis/list-empty-marker))
        result (nb/run-propagators seeded props)
        repeated (nb/run-propagators result (:props compiled))]
    (is (value/nothing? (net/network-cell-strongest initial answer)))
    (is (= [9] (functional/values result answer)))
    (is (= (net/net-graph result) (net/net-graph repeated)))
    (is (= [9] (functional/values repeated answer)))))

(deftest apply-preserves-known-closed-interface-validation
  (doseq [source ["(apply (network [x] x) (list))"
                  "(apply (network [x] x) (list 1 2))"]]
    (testing source
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid arity"
                           (evaluate source)))))
  (is (= 5
         (let [compiled (compiler/compile-source "(apply + (list 2 3))")
               result (functional/run compiled)]
           (net/network-cell-strongest result (:cell compiled))))))

(deftest supported-operator-selection-keeps-only-the-current-call-interface
  (doseq [ingress [:publication :membership]]
    (testing ingress
      (let [loaded (acceptance/load-session
                    "(def-cells flag tail)
                       (define fixed (network [x] (+ x 1)))
                       (define rest (network [& xs] (car xs)))
                       (define selected (if flag fixed rest))
                       (define answer (apply selected (cons 4 tail)))")]
        (is (value/nothing? (acceptance/observed loaded 'answer)))
        (doseq [[revision flag expected] [[1 true 5] [2 false 4]
                                        [3 value/nothing value/nothing] [4 true 5]]]
          (acceptance/publish! loaded ingress 'flag revision flag)
          (is (= expected (acceptance/observed loaded 'answer))))))))

(deftest argument-owner-availability-gates-even-a-constant-return
  (doseq [ingress [:publication :membership]]
    (let [loaded (acceptance/load-session
                  "(def-cells flag unknown-head left-tail right-tail)
                   (define selected (if flag (cons unknown-head left-tail)
                                             (cons 9 right-tail)))
                   (define answer (apply (network [x] 42) selected))")]
      (doseq [[revision flag expected]
              [[1 true 42] [2 value/nothing value/nothing]
               [3 false 42] [4 value/nothing value/nothing] [5 true 42]]]
        (acceptance/publish! loaded ingress 'flag revision flag)
        (is (= expected (acceptance/observed loaded 'answer)))))))

(deftest supported-list-selection-rereads-current-argument-references
  (doseq [ingress [:publication :membership]]
    (testing ingress
      (let [loaded (acceptance/load-session
                    "(def-cells flag left-value right-value left-tail right-tail)
                       (define left (cons left-value left-tail))
                       (define right (cons right-value right-tail))
                       (define selected (if flag left right))
                       (define answer (apply (network [x] (+ x 1)) selected))")]
        (acceptance/publish! loaded ingress 'left-value 1 4)
        (acceptance/publish! loaded ingress 'right-value 1 10)
        (doseq [[name revision candidate expected]
                [['flag 1 true 5] ['flag 2 false 11] ['left-value 2 8 11]
                 ['flag 3 value/nothing value/nothing] ['flag 4 true 9]]]
          (acceptance/publish! loaded ingress name revision candidate)
          (is (= expected (acceptance/observed loaded 'answer))))))))

(deftest open-fixed-calls-preserve-reverse-constraints
  (doseq [seeds ["(-> 2 a) (-> 3 b)" "(-> 3 b) (-> 5 c)" "(-> 2 a) (-> 5 c)"]]
    (let [compiled (compiler/compile-source
                    (str "(let-cell [a b c tail] " functional/addition-definition
                         seeds " (apply constrain-+ (cons a (cons b (cons c tail))))"
                         " (list a b c))"))
          result (functional/run compiled)]
      (is (= [2 3 5] (functional/values result (:cell compiled)))))))

(deftest unspecified-primitive-arity-waits-for-an-explicit-end
  (let [compiled (compiler/compile-source
                  "(let-cell [tail] (list tail (apply + (cons 2 (cons 3 tail)))))")
        initial (functional/run compiled)
        [tail answer] (functional/list-ids initial (:cell compiled))
        closed (nb/seed-cell initial tail basis/list-empty-marker)
        result (nb/run-propagators closed (nb/neighbor-propagator-ids closed tail))]
    (is (value/nothing? (net/network-cell-strongest initial answer)))
    (is (= 5 (net/network-cell-strongest result answer)))))

(deftest unspecified-arity-completion-withdraws-with-its-known-end
  (doseq [ingress [:publication :membership]]
    (let [loaded (acceptance/load-session
                  "(def-cells end)
                   (define args (cons 2 (cons 3 end)))
                   (define primitive (apply + args))
                   (define fixed (apply (network [x y] (+ x y)) args))")]
      (is (value/nothing? (acceptance/observed loaded 'primitive)))
      (is (= 5 (acceptance/observed loaded 'fixed)))
      (doseq [[revision candidate expected]
              [[1 basis/list-empty-marker 5] [2 value/nothing value/nothing]
               [3 basis/list-empty-marker 5]]]
        (acceptance/publish! loaded ingress 'end revision candidate)
        (is (= expected (acceptance/observed loaded 'primitive)))
        (is (= 5 (acceptance/observed loaded 'fixed)))))))

(deftest known-extra-and-malformed-tail-information-is-validated
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"topology-backed linked list"
                       (evaluate "(apply (network [x] x) (cons 1 99))")))
  (let [compiled (compiler/compile-source
                  "(let-cell [tail] (list tail (apply (network [x] x) (cons 1 tail))))")
        initial (functional/run compiled)
        [tail answer] (functional/list-ids initial (:cell compiled))
        [head end] (mapv gur/stable-node-id [[::extra-head] [::extra-end]])
        prepared (reduce nb/ensure-cell initial [head end])
        [props declared] ((object/p:cons head end tail) prepared)]
    (is (= 1 (net/network-cell-strongest initial answer)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid arity"
                         (nb/run-propagators declared props)))))

(deftest apply-wakes-when-a-fixed-operator-arrives-late
  (let [compiled (compiler/compile-source
                  "(let-cell [f tail]
                     (list f (network [x] (+ x 1)) (apply f (cons 4 tail))))")
        initial (functional/run compiled)
        [operator declaration answer] (functional/list-ids initial (:cell compiled))
        seeded (nb/seed-cell initial operator (net/network-cell-strongest initial declaration))
        result (nb/run-propagators seeded (nb/neighbor-propagator-ids seeded operator))]
    (is (value/nothing? (net/network-cell-strongest initial answer)))
    (is (= 5 (net/network-cell-strongest result answer)))))
