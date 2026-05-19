(ns propagators.network-test
  (:refer-clojure :exclude [partial])
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells :refer [->Cell cell?]]
            [propagators.cells.value :refer [cell-value-equal? partial]]
            [propagators.compile :refer [cell-ref compile-net prop-ref]]
            [propagators.core :refer [run-tasks]]
            [propagators.graph :refer [get-node]]
            [propagators.helpers.task-queue :as tq]
            [propagators.propagator :refer [propagator?]]))

(defn- seed-cell
  [[graph env] cell-id value]
  (let [cv (partial value)]
    [graph (assoc env cell-id (->Cell cv cv))]))

(defn- strongest
  [env cell-id]
  (:strongest (get env cell-id)))

(defn- run-from
  [[graph env] prop-id]
  (let [pn (get-node graph prop-id)
        [g e] (run-tasks (tq/enqueue tq/empty-queue pn) [graph env])]
    [g e]))

(defn- dump-net
  "Debug helper for failure analysis."
  [graph env]
  {:graph-nodes (into {}
                      (map (fn [[id n]]
                             [id {:inputs (:inputs n) :outputs (:outputs n)}])
                           graph))
   :env (into {}
              (map (fn [[id v]]
                     [id (cond
                           (cell? v) {:kind :cell :strongest (:strongest v)}
                           (propagator? v) {:kind :propagator}
                           :else {:kind :unknown})])
                   env))})

(deftest sync-chain-propagates-value
  (let [net (compile-net
             '(let [c0 (cell)
                    c1 (cell)
                    c2 (cell)]
                (do (p:id c0 c1)
                    (p:id c1 c2))))
        {:keys [graph env]} net
        c0 (cell-ref net 'c0)
        c1 (cell-ref net 'c1)
        c2 (cell-ref net 'c2)
        p01 (prop-ref net 0)
        p12 (prop-ref net 1)
        [g e] (seed-cell [graph env] c0 42)
        expected (partial 42)
        [g e] (run-from [g e] p01)]
    (is (cell-value-equal? expected (strongest e c1))
        (str "c1 after p01 " (dump-net g e)))
    (let [[g e] (run-from [g e] p12)]
      (is (cell-value-equal? expected (strongest e c2))
          (str "c2 after p12 " (dump-net g e))))))

(deftest bi-sync-one-activation
  (let [net (compile-net
             '(let [cA (cell)
                    cB (cell)]
                (do (p:id cA cB)
                    (p:id cB cA))))
        {:keys [graph env]} net
        cA (cell-ref net 'cA)
        cB (cell-ref net 'cB)
        pAB (prop-ref net 0)
        seed (partial 1)
        [g e] (seed-cell [graph env] cA 1)
        [g e] (run-from [g e] pAB)]
    (is (cell-value-equal? seed (strongest e cB))
        (str "cB after pAB " (dump-net g e)))
    (is (cell-value-equal? seed (strongest e cA))
        (str "cA unchanged " (dump-net g e)))))

(deftest bi-sync-chain-three-cells
  (let [net (compile-net
             '(let [c0 (cell)
                    c1 (cell)
                    c2 (cell)]
                (do (p:id c0 c1)
                    (p:id c1 c0)
                    (p:id c1 c2)
                    (p:id c2 c1))))
        {:keys [graph env]} net
        c0 (cell-ref net 'c0)
        c1 (cell-ref net 'c1)
        c2 (cell-ref net 'c2)
        p01 (prop-ref net 0)
        p12 (prop-ref net 2)
        expected (partial 99)
        [g e] (seed-cell [graph env] c0 99)
        [g e] (run-from [g e] p01)]
    (is (cell-value-equal? expected (strongest e c1))
        (str "c1 after p01 " (dump-net g e)))
    (let [[g e] (run-from [g e] p12)]
      (is (cell-value-equal? expected (strongest e c2))
          (str "c2 after p12 " (dump-net g e))))))

(defn -main [& _]
  (let [{:keys [fail error pass]} (clojure.test/run-tests 'propagators.network-test)]
    (println "propagators.network-test:" pass "pass," fail "fail," error "error")
    (when (or (pos? fail) (pos? error)) (System/exit 1))))
