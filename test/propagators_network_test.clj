(ns propagators-network-test
  (:refer-clojure :exclude [partial])
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells :refer [->Cell cell-merge cell-strongest cell?]]
            [propagators.cells.value :refer [any-unusable-values? cell-value-equal? complete partial]]
            [propagators.compile :refer [cell-ref compile-net prop-ref]]
            [propagators.core :refer [run-tasks]]
            [propagators.graph :refer [get-node node]]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :refer [compound-propagator construct-cell]]
            [propagators.propagator :refer [propagator?]]
            [propagators.stdlib :refer [bi-sync-closure p:id]]))

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

;; --- compound propagator test harness ---

(defn- install-cell [[graph env] id]
  (let [[_ [g e]] ((construct-cell id) [graph env])
        g (assoc g id (node id #{} #{}))]
    [g e]))

(defn- install-inner-bi-sync [a-id b-id]
  "Inner network: bi-sync between `a-id` and `b-id` (same ids as outer boundary ports)."
  (let [[g e] (install-cell [{} {}] a-id)
        [g e] (install-cell [g e] b-id)
        [_ [g e]] ((p:id [a-id b-id]) [g e])
        [_ [g e]] ((p:id [b-id a-id]) [g e])]
    [g e]))

(defn- boundary-inject [boundary-ids]
  "Closure body: merge boundary snapshots into inner `env` at matching node ids."
  (fn [[graph env] input-snapshots output-snapshots]
    (let [env' (reduce
                (fn [e snap]
                  (let [[node {:keys [strongest]}] snap
                        id (:id node)]
                    (if (and (contains? boundary-ids id)
                             strongest
                             (not (any-unusable-values? strongest)))
                      (let [{:keys [content]} (get e id)
                            content' (cell-merge content strongest)
                            strongest' (cell-strongest content')]
                        (assoc e id (->Cell content' strongest')))
                      e)))
                env
                (concat input-snapshots output-snapshots))]
      [graph env'])))

(defn- seed-closure-cell [[graph env] closure-id f inner-graph inner-env]
  (let [cv (complete [f [inner-graph inner-env]])]
    [graph (assoc env closure-id (->Cell cv cv))]))

(defn- install-compound [[graph env] closure-id inputs outputs]
  (let [[prop-id [g e]] ((compound-propagator closure-id inputs outputs)
                          [graph env])]
    [prop-id [g e]]))

(defn- seed-stdlib-bi-sync-closure [[graph env] closure-id]
  (let [cv (complete bi-sync-closure)]
    [graph (assoc env closure-id (->Cell cv cv))]))

(defn- build-stdlib-compound-chain
  "Outer c0 -compound(k0)- c1 -compound(k1)- c2.
  Closure cells hold `bi-sync-closure` from stdlib (`[bi-sync empty-network]`)."
  []
  (let [c0 (new-node-id)
        c1 (new-node-id)
        c2 (new-node-id)
        k0 (new-node-id)
        k1 (new-node-id)
        [g e] (install-cell [{} {}] c0)
        [g e] (install-cell [g e] c1)
        [g e] (install-cell [g e] c2)
        [g e] (install-cell [g e] k0)
        [g e] (install-cell [g e] k1)
        [g e] (seed-stdlib-bi-sync-closure [g e] k0)
        [g e] (seed-stdlib-bi-sync-closure [g e] k1)
        [p01 [g e]] (install-compound [g e] k0 [c0] [c1])
        [p12 [g e]] (install-compound [g e] k1 [c1] [c2])]
    {:graph g :env e
     :cells {:c0 c0 :c1 c1 :c2 c2 :k0 k0 :k1 k1}
     :props [p01 p12]}))

(defn- build-compound-bi-sync-chain
  "Outer chain c0 --compound(k0)--> c1 --compound(k1)--> c2.
  Each compound wraps an inner bi-sync on its boundary port ids."
  []
  (let [c0 (new-node-id)
        c1 (new-node-id)
        c2 (new-node-id)
        k0 (new-node-id)
        k1 (new-node-id)
        [ig0 ie0] (install-inner-bi-sync c0 c1)
        [ig1 ie1] (install-inner-bi-sync c1 c2)
        f0 (boundary-inject #{c0 c1})
        f1 (boundary-inject #{c1 c2})
        [g e] (install-cell [{} {}] c0)
        [g e] (install-cell [g e] c1)
        [g e] (install-cell [g e] c2)
        [g e] (install-cell [g e] k0)
        [g e] (install-cell [g e] k1)
        [g e] (seed-closure-cell [g e] k0 f0 ig0 ie0)
        [g e] (seed-closure-cell [g e] k1 f1 ig1 ie1)
        [p01 [g e]] (install-compound [g e] k0 [c0] [c1])
        [p12 [g e]] (install-compound [g e] k1 [c1] [c2])]
    {:graph g :env e
     :cells {:c0 c0 :c1 c1 :c2 c2 :k0 k0 :k1 k1}
     :props [p01 p12]}))

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

(deftest stdlib-bi-sync-closure-compound-single
  (testing "compound with closure cell = complete bi-sync-closure; seed c0, run once"
    (let [{:keys [graph env cells props]} (build-stdlib-compound-chain)
          {:keys [c0 c1]} cells
          p01 (first props)
          expected (partial 7)
          [g e] (seed-cell [graph env] c0 7)
          [g e] (run-from [g e] p01)]
      (is (cell-value-equal? expected (strongest e c1))
          (str "c1 after stdlib bi-sync compound " (dump-net g e)))
      (is (cell-value-equal? expected (strongest e c0))
          (str "c0 after compound (bi-sync) " (dump-net g e))))))

(deftest stdlib-bi-sync-closure-compound-chain
  (testing "two stdlib bi-sync-closure compounds: c0 -> c1 -> c2"
    (let [{:keys [graph env cells props]} (build-stdlib-compound-chain)
          {:keys [c0 c1 c2]} cells
          [p01 p12] props
          expected (partial 99)
          [g e] (seed-cell [graph env] c0 99)
          [g e] (run-from [g e] p01)]
      (is (cell-value-equal? expected (strongest e c1))
          (str "c1 after first stdlib compound " (dump-net g e)))
      (let [[g e] (run-from [g e] p12)]
        (is (cell-value-equal? expected (strongest e c2))
            (str "c2 after second stdlib compound " (dump-net g e)))))))

(deftest compound-bi-sync-single-hop
  (testing "one compound: inner bi-sync on boundary ids, outer c0 -> c1"
    (let [{:keys [graph env cells props]} (build-compound-bi-sync-chain)
          {:keys [c0 c1]} cells
          p01 (first props)
          expected (partial 7)
          [g e] (seed-cell [graph env] c0 7)
          [g e] (run-from [g e] p01)]
      (is (cell-value-equal? expected (strongest e c1))
          (str "c1 after compound " (dump-net g e)))
      (is (cell-value-equal? expected (strongest e c0))
          (str "c0 after compound (bi-sync should hold) " (dump-net g e)))))

(deftest compound-bi-sync-chain
  (testing "two compounds: c0 -compound-> c1 -compound-> c2"
    (let [{:keys [graph env cells props]} (build-compound-bi-sync-chain)
          {:keys [c0 c1 c2]} cells
          [p01 p12] props
          expected (partial 99)
          [g e] (seed-cell [graph env] c0 99)
          [g e] (run-from [g e] p01)]
      (is (cell-value-equal? expected (strongest e c1))
          (str "c1 after first compound " (dump-net g e)))
      (let [[g e] (run-from [g e] p12)]
        (is (cell-value-equal? expected (strongest e c2))
            (str "c2 after second compound " (dump-net g e))))))))

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
