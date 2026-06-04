(ns propagators-recursive-compound-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.compile :as compile]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.recursive :as recursive]
            [propagators.stdlib.prop :as stdlib-prop]))

(defn- strongest
  [n id]
  (net/network-cell-strongest n id))

(defn- seed-output
  [n out-id v]
  (nb/seed-cell n out-id v))

(defn- run-installed
  [n prop-ids]
  (nb/run-propagators n prop-ids))

(defn- eval-dsl
  [n sym->value expr]
  (compile/eval-layered n (compile/default-installers) sym->value expr))

(defn- eval-and-run
  [n sym->value expr]
  (let [{:keys [net props] :as ctx} (eval-dsl n sym->value expr)]
    (assoc ctx :net (run-installed net props))))

(defn- fib-step
  [branch-builds]
  (fn [{:keys [self-id input-ids output-ids network]}]
    (let [[n-id] input-ids
          [out-id] output-ids
          n-value (strongest network n-id)]
      (cond
        (value/unusable? n-value)
        network

        (not (and (integer? n-value) (not (neg? n-value))))
        (seed-output network out-id value/contradiction)

        :else
        (let [base? (<= n-value 1)
              {:keys [net]} (eval-and-run
                             network
                             {'n n-id
                              'out out-id
                              'base-value base?}
                             '(do
                                (let-cell [base?]
                                  (seed base? base-value)
                                  (prop/switch n base? out))))
              n2 net]
          (if-not (value/nothing? (strongest n2 out-id))
            n2
            (do
              (swap! branch-builds inc)
              (let [ctx (eval-and-run
                         n2
                         {'self self-id
                          'out out-id
                          'n-minus-1 (dec n-value)
                          'n-minus-2 (- n-value 2)}
                         '(do
                            (let-cell [n-1 n-2 fib-1 fib-2]
                              (seed n-1 n-minus-1)
                              (seed n-2 n-minus-2)
                              (recursive/p:recursive-compound self n-1 fib-1)
                              (recursive/p:recursive-compound self n-2 fib-2))))
                    n6 (:net ctx)
                    fib-1-id (compile/cell-ref ctx 'fib-1)
                    fib-2-id (compile/cell-ref ctx 'fib-2)
                    fib-1 (strongest n6 fib-1-id)
                    fib-2 (strongest n6 fib-2-id)]
                (cond
                  (or (value/contradiction? fib-1)
                      (value/contradiction? fib-2))
                  (seed-output n6 out-id value/contradiction)

                  (or (value/unusable? fib-1)
                      (value/unusable? fib-2))
                  n6

                  :else
                  (:net (eval-and-run
                         n6
                         {'fib-1 fib-1-id
                          'fib-2 fib-2-id
                          'out out-id}
                         '(prop/+ fib-1 fib-2 out))))))))))))

(defn- fib-recursive-closure
  ([]
   (fib-recursive-closure {}))
  ([opts]
   (let [branch-builds (or (:branch-builds opts) (atom 0))]
     (recursive/recursive-closure (fib-step branch-builds)
                                  (select-keys opts [:max-depth])))))

(defn- run-fib
  ([n-value]
   (run-fib n-value {}))
  ([n-value opts]
   (let [closure-value (fib-recursive-closure opts)
         ctx (eval-and-run
              net/empty-net
              {'closure-value closure-value
               'n-value n-value}
              '(do
                 (let-cell [fib n out]
                   (seed fib closure-value)
                   (seed n n-value)
                   (recursive/p:recursive-compound fib n out))))
         out-id (compile/cell-ref ctx 'out)]
     {:net (:net ctx)
      :out-id out-id
      :value (strongest (:net ctx) out-id)})))

(defn- run-fib-unusable-input
  []
  (let [closure-value (fib-recursive-closure)
        ctx (eval-and-run
             net/empty-net
             {'closure-value closure-value}
             '(do
                (let-cell [fib n out]
                  (seed fib closure-value)
                  (recursive/p:recursive-compound fib n out))))
        out-id (compile/cell-ref ctx 'out)]
    {:net (:net ctx)
     :out-id out-id
     :value (strongest (:net ctx) out-id)}))

(defn- wrapper-closure
  [fib-closure-value]
  (closure/closure
   (fn [_closure-net input-ids output-ids network]
     (let [[n-id] input-ids
           [out-id] output-ids
           ctx (eval-and-run
                network
                {'fib-value fib-closure-value
                 'n n-id
                 'out out-id}
                '(do
                   (let-cell [fib]
                     (seed fib fib-value)
                     (recursive/p:recursive-compound fib n out))))]
       (:net ctx)))
   net/empty-net))

(deftest recursive-fibonacci-values
  (testing "concrete numeric fibonacci values"
    (is (= 0 (:value (run-fib 0))))
    (is (= 1 (:value (run-fib 1))))
    (is (= 1 (:value (run-fib 2))))
    (is (= 5 (:value (run-fib 5))))
    (is (= 55 (:value (run-fib 10))))))

(deftest recursive-compound-wires-through-compile-dsl
  (testing "compile DSL installs and runs recursive compound propagators"
    (is (= 3 (:value (run-fib 4))))))

(deftest recursive-fibonacci-lazy-base-branch
  (testing "base branch fills output without building recursive topology"
    (let [branch-builds (atom 0)
          result (run-fib 1 {:branch-builds branch-builds})]
      (is (= 1 (:value result)))
      (is (= 0 @branch-builds)))))

(deftest recursive-fibonacci-failure-cases
  (testing "negative input contradicts"
    (is (= value/contradiction (:value (run-fib -1)))))

  (testing "unusable input leaves output empty"
    (is (= value/nothing (:value (run-fib-unusable-input)))))

  (testing "depth overflow contradicts recursive input"
    (is (= value/contradiction (:value (run-fib 2 {:max-depth 1}))))))

(deftest recursive-propagator-inside-normal-compound
  (testing "a normal compound closure can run one recursive compound application"
    (let [wrapper-value (wrapper-closure (fib-recursive-closure))
          ctx (eval-and-run
               net/empty-net
               {'wrapper-value wrapper-value
                'n-value 7}
               '(do
                  (let-cell [wrapper n out]
                    (seed wrapper wrapper-value)
                    (seed n n-value)
                    (closure/p:apply-closure wrapper n out))))
          out-id (compile/cell-ref ctx 'out)]
      (is (= 13 (strongest (:net ctx) out-id))))))
