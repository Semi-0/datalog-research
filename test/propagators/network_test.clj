(ns propagators.network-test
  (:refer-clojure :exclude [partial])
  (:require [clojure.test :refer [deftest is]]
            [propagators.cell :refer [->Cell cell?]]
            [propagators.cell-value :refer [cell-value-equal? partial]]
            [propagators.core :refer [run-tasks]]
            [propagators.graph :refer [get-node node]]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :refer [construct-cell p:id]]
            [propagators.propagator :refer [propagator?]]))

(defn- empty-net [] [{} {}])

(defn- install-cell
  "Add cell to env and blank graph node."
  [[graph env] cell-id]
  (let [[_ [g e]] ((construct-cell cell-id) [graph env])
        g (assoc g cell-id (node cell-id #{} #{}))]
    [g e]))

(defn- install-sync
  "Install `p:id` from `in-id` to `out-id`. Returns `[prop-id graph env]`."
  [[graph env] in-id out-id]
  ;; p:id is (primitive-propagator f) → (fn [args] …); args = [inputs… out-id]
  (let [[prop-id [g e]] ((p:id [in-id out-id]) [graph env])]
    [prop-id g e]))

(defn- seed-cell
  [[graph env] cell-id value]
  (let [cv (partial value)]
    [graph (assoc env cell-id (->Cell cv cv))]))

(defn- strongest
  [env cell-id]
  (:strongest (get env cell-id)))

(defn- prop-node [graph prop-id]
  (get-node graph prop-id))

(defn- run-from
  [[graph env] prop-id]
  (let [pn (prop-node graph prop-id)
        [g e] (run-tasks #{pn} [graph env])]
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
  ;; c0 -p01-> c1 -p12-> c2; manual p01 then p12
  (let [c0 (new-node-id)
        c1 (new-node-id)
        c2 (new-node-id)
        net (empty-net)
        [g e] (-> net (install-cell c0) (install-cell c1) (install-cell c2))
        [p01 g e] (install-sync [g e] c0 c1)
        [p12 g e] (install-sync [g e] c1 c2)
        [g e] (seed-cell [g e] c0 42)
        expected (partial 42)
        [g e] (run-from [g e] p01)]
    (is (cell-value-equal? expected (strongest e c1))
        (str "c1 after p01 " (dump-net g e)))
    (let [[g e] (run-from [g e] p12)]
      (is (cell-value-equal? expected (strongest e c2))
          (str "c2 after p12 " (dump-net g e))))))

(deftest bi-sync-one-activation
  ;; cA <-> cB; activate pAB only
  (let [cA (new-node-id)
        cB (new-node-id)
        net (empty-net)
        [g e] (-> net (install-cell cA) (install-cell cB))
        [pAB g e] (install-sync [g e] cA cB)
        [_pBA g e] (install-sync [g e] cB cA)
        seed (partial 1)
        [g e] (seed-cell [g e] cA 1)
        [g e] (run-from [g e] pAB)]
    (is (cell-value-equal? seed (strongest e cB))
        (str "cB after pAB " (dump-net g e)))
    (is (cell-value-equal? seed (strongest e cA))
        (str "cA unchanged " (dump-net g e)))))

(deftest bi-sync-chain-three-cells
  ;; c0 <-> c1 <-> c2; manual p01 then p12 (forward along chain)
  (let [c0 (new-node-id)
        c1 (new-node-id)
        c2 (new-node-id)
        net (empty-net)
        [g e] (-> net (install-cell c0) (install-cell c1) (install-cell c2))
        [p01 g e] (install-sync [g e] c0 c1)
        [_p10 g e] (install-sync [g e] c1 c0)
        [p12 g e] (install-sync [g e] c1 c2)
        [_p21 g e] (install-sync [g e] c2 c1)
        expected (partial 99)
        [g e] (seed-cell [g e] c0 99)
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
