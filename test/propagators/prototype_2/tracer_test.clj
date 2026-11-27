(ns propagators.prototype-2.tracer-test
  "L-2 origins/neighbors primitives + recursive reason/trace."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.prototype-2.compiler :as comp]
            [propagators.prototype-2.core :as p2]
            [propagators.prototype-2.p-list :as plist]))

(defn- p-list-values
  "Host vector of car values along a Church p-list handle."
  [network v]
  (cond
    (plist/empty-list? v) []
    (plist/church-cons? v)
    (into [(p2/cell-value network (:a v))]
          (p-list-values network (p2/cell-value network (:b v))))
    :else []))

(defn- run-on
  "Compile `forms` against an existing network; return [value network state]."
  [network & forms]
  (let [state (comp/compile-program (comp/fresh-state network) (vec forms))
        network (p2/run-tasks (:network state))
        value (p2/cell-value network (:result state))]
    [value network state]))

(deftest origins-neighbors-values
  (testing "primitive origins / neighbors over a settled id-chain"
    (let [cells [:c0 :c1 :c2 :c3 :c4 :c5]
          network (p2/install-id-chain p2/empty-network cells)
          [_ network] (p2/tell network :c0 42)
          network (p2/run-tasks network)
          [ov onet] (run-on network '(origins :c5))
          [nv nnet] (run-on network '(neighbors :c5))
          [n0 n0net] (run-on network '(neighbors :c0))]
      (is (= [:c0] (p-list-values onet ov)))
      (is (= [:c4] (p-list-values nnet nv)))
      (is (= [] (p-list-values n0net n0))))))

(deftest l2-recursive-trace-and-reason
  (testing "L-2 prelude: reason = origins; trace = origin→end via neighbors"
    (let [cells [:c0 :c1 :c2 :c3 :c4 :c5]
          network (p2/install-id-chain p2/empty-network cells)
          [_ network] (p2/tell network :c0 99)
          network (p2/run-tasks network)
          forms (into (vec comp/l2-tracer-prelude) '[(trace :c5)])
          [tv tnet] (apply run-on network forms)
          forms-r (into (vec comp/l2-tracer-prelude) '[(reason :c5)])
          [rv rnet] (apply run-on network forms-r)]
      (is (= [:c0] (p-list-values rnet rv)))
      (is (= [:c0 :c1 :c2 :c3 :c4 :c5] (p-list-values tnet tv))))))

(deftest l2-reason-of-compiled-arithmetic
  (testing "reason of a bound L-2 result reaches literal origins"
    (let [forms (into (vec comp/l2-tracer-prelude)
                       '[(define r (+ (* 2 3) 1))
                         (reason r)])
          [rv rnet] (apply run-on p2/empty-network forms)
          origins (p-list-values rnet rv)]
      (is (seq origins))
      (is (every? keyword? origins))
      ;; every origin cell holds a literal that fed the expression
      (is (= #{2 3 1}
             (set (map #(p2/cell-value rnet %) origins)))))))
