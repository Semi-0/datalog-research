(ns propagators.experimental.amb-swappable-solver-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.solver-lain :as lain]
            [examples.lain.amb.solver-operator :as operator]
            [examples.lain.amb.solver-strategy :as strategy]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.experimental.amb.observation :as observation]
            [propagators.network :as net]
            [propagators.network-cache :as cache]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn query [network]
  (let [props (filter (fn [[_ entry]]
                        (and (prop/prop? entry) (= ::operator/prepare (prop/prop-name entry))))
                      (net/net-env network))]
    (if (= 1 (count props))
      (let [id (ffirst props) output (first (:outputs (get (net/net-graph network) id)))]
        (:question (observation/cell-base network output)))
      (throw (ex-info "Expected one prepared query" {})))))

(deftest compile-once-switch-strategies-with-the-same-question
  (let [compiled (lain/compile-source (lain/source-for 5))
        initial (binding [cache/*cache* nil] (lain/prepare compiled))
        shared (query (:network initial))
        calls (atom []) pool (driver/pool 4)]
    (try
      (binding [strategy/*executor* pool cache/*cache* nil
                operator/*observe-solve* #(swap! calls conj %)]
        (let [last-net
              (reduce
               (fn [network [index choice]]
                 (let [result (lain/select-and-run compiled network choice)]
                   (is (= :completed (:status result)))
                   (is (= [[3 4 5]] (:answers result)))
                   (is (= (inc index) (count @calls)))
                   (is (identical? shared (:question (last @calls))))
                   (is (identical? (query network) (query (:network result))))
                   (is (= (net/net-graph network) (net/net-graph (:network result))))
                   (is (every? #(value/nothing? (net/network-cell-strongest (:network result) %))
                               (:sources shared)))
                   (:network result)))
               (:network initial) (map-indexed vector [:checkpoint :repair :concurrent :checkpoint]))
              duplicate (lain/select-and-run compiled last-net :checkpoint)]
          (is (= 4 (count @calls)))
          (is (= (net/net-env last-net) (net/net-env (:network duplicate))))
          (is (= (net/net-graph last-net) (net/net-graph (:network duplicate))))
          (is (application/compiler-callable?
               (net/network-cell-strongest (:network shared) (:callable shared))))
          (is (every? #(satisfies? strategy/ConstraintSolver (:solver %)) @calls))
          (is (every? #(= {:workers 4} (:context %)) @calls))
          (is (= (vec (sort (oracle/exhaustive 5))) (:answers duplicate)))))
      (finally (driver/close! pool)))))

(deftest exhaustion-and-multiple-solutions-have-the-same-protocol
  (let [pool (driver/pool 4)
        all-true "(let-cell [a b c] (define constraint (network (a b c) true))
                    (solve solver constraint (list 1 2) a b c))"
        expected (vec (for [a [1 2] b [1 2] c [1 2]] [a b c]))]
    (try
      (binding [strategy/*executor* pool cache/*cache* nil]
        (doseq [[source answers] [[(lain/source-for 4) []] [all-true expected]]]
          (let [compiled (lain/compile-source source) initial (lain/prepare compiled)]
            (doseq [choice [:repair :checkpoint :concurrent]]
              (testing (str choice)
                (let [result (lain/select-and-run compiled (:network initial) choice)]
                  (is (= :completed (:status result)))
                  (is (= answers (:answers result)))))))))
      (finally (driver/close! pool)))))

(deftest lexical-capture-stays-part-of-the-question-not-strategy-wiring
  (let [source "(let-cell [a b c] (define target 3)
                  (define constraint (network (a b c) (= (+ a b c) target)))
                  (solve solver constraint (list 1 2) a b c))"
        compiled (lain/compile-source source) initial (lain/prepare compiled) pool (driver/pool 4)]
    (try
      (binding [strategy/*executor* pool cache/*cache* nil]
        (doseq [choice [:repair :checkpoint :concurrent]]
          (is (= [[1 1 1]] (:answers (lain/select-and-run compiled (:network initial) choice))))))
      (finally (driver/close! pool)))))

(deftest late-selection-is-a-cell-update-without-recompilation
  (let [compiled (lain/compile-source (lain/source-for 1))
        waiting (lain/run-state compiled {:network (:net compiled) :tasks (:props compiled)})]
    (is (= :pending (:status waiting)))
    (is (map? (query (:network waiting))))
    (is (= [] (:answers (lain/select-and-run compiled (:network waiting) :checkpoint))))))

(deftest errors-are-not-silently-converted-into-unsatisfiable
  (is (thrown? clojure.lang.ExceptionInfo (strategy/select :unknown)))
  (is (thrown? clojure.lang.ExceptionInfo (operator/arguments [])))
  (is (thrown? clojure.lang.ExceptionInfo (operator/solve-binding {:workers 0})))
  (is (thrown? clojure.lang.ExceptionInfo (lain/source-for 0)))
  (let [compiled (lain/compile-source (lain/source-for 1)) initial (lain/prepare compiled)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"runtime executor"
                         (lain/select-and-run compiled (:network initial) :concurrent))))
  (doseq [source [(str/replace (lain/source-for 1) "(list 1)" "(list)")
                  (str/replace (lain/source-for 1) "(list 1)" "(list 1 1)")
                  "(let-cell [a b c] (define constraint 42)
                       (solve solver constraint (list 1) a b c))"
                  "(let-cell [a b c] (define a 1)
                       (define constraint (network (a b c) true))
                       (solve solver constraint (list 1) a b c))"]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (let [compiled (lain/compile-source source)]
                   (lain/select-and-run compiled (:net compiled) :checkpoint)))))
  (let [compiled (lain/compile-source (lain/source-for 1))
        bad (nb/seed-cell (:net compiled) (:solver-cell compiled) :not-a-solver)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ConstraintSolver"
                         (lain/run-state compiled {:network bad :tasks (:props compiled)})))))
