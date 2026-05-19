(ns propagators-network-test
  (:refer-clojure :exclude [partial])
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :refer [cell-value-equal? complete partial]]
            [propagators.closure :refer [compound-propagator]]
            [propagators.compile :refer [cell-ref compile-net net-let prop-ref]]
            [propagators.core :refer [run-tasks]]
            [propagators.graph :refer [get-node node-input-ids node-output-ids]]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net :refer [construct-cell]]
            [propagators.propagator :refer [prop?]]
            [propagators.stdlib :refer [bi-sync-closure p:id]]))

;; --- harness ---

(defn- net-of-ctx [ctx]
  (net/net (:graph ctx) (:env ctx)))

(defn- with-compiled [expr f]
  (let [ctx (compile-net expr)]
    (f {:net (net-of-ctx ctx) :ctx ctx})))

(defn- strongest [env cell-id]
  (cell/cell-strongest (net/env-get env cell-id)))

(defn- seed-cell [n cell-id value]
  (let [cv (partial value)]
    (net/assoc-net-cell n cell-id (cell/cell cv cv))))

(defn- run-prop [n prop-id]
  (let [node (get-node (net/net-graph n) prop-id)]
    (run-tasks (tq/enqueue tq/empty-queue node) n)))

(defn- dump-net [n]
  (let [graph (net/net-graph n)
        env (net/net-env n)]
    {:graph-nodes (into {}
                        (map (fn [[id node]]
                               [id {:inputs (node-input-ids node)
                                    :outputs (node-output-ids node)}])
                             graph))
     :env (into {}
                (map (fn [[id v]]
                       [id (cond
                             (cell/cell? v) {:kind :cell :strongest (cell/cell-strongest v)}
                             (prop? v) {:kind :propagator}
                             :else {:kind :unknown})])
                     env))}))

(defn- expect-strongest [n cell-id expected label]
  (is (cell-value-equal? expected (strongest (net/net-env n) cell-id))
      (str label " " (dump-net n))))

;; --- runtime network builders (net-let) ---

(defn- install-compound [n closure-id inputs outputs]
  (let [[prop-id n'] ((compound-propagator closure-id inputs outputs) n)]
    [prop-id n']))

(defn- three-cell-line [c0 c1 c2 k0 k1]
  (net-let net/empty-net
    [[_c0 c0]
     [_c1 c1]
     [_c2 c2]
     [_k0 k0]
     [_k1 k1]]))

(defn- build-stdlib-compound-chain []
  (let [c0 (new-node-id)
        c1 (new-node-id)
        c2 (new-node-id)
        k0 (new-node-id)
        k1 (new-node-id)
        cv (complete bi-sync-closure)
        n (-> (three-cell-line c0 c1 c2 k0 k1)
              (net/assoc-net-cell k0 (cell/cell cv cv))
              (net/assoc-net-cell k1 (cell/cell cv cv)))
        [p01 n] (install-compound n k0 [c0 c1] [c0 c1])
        [p12 n] (install-compound n k1 [c1 c2] [c1 c2])]
    {:net n :cells {:c0 c0 :c1 c1 :c2 c2} :props [p01 p12]}))

(defn- build-stdlib-compound-abc-with-inject []
  "Chain a <-> b <-> c (compounds k0, k1) plus injector cell e --p:id--> b."
  (let [a (new-node-id)
        b (new-node-id)
        c (new-node-id)
        e (new-node-id)
        k0 (new-node-id)
        k1 (new-node-id)
        cv (complete bi-sync-closure)
        n (-> (three-cell-line a b c k0 k1)
              (net/assoc-net-cell k0 (cell/cell cv cv))
              (net/assoc-net-cell k1 (cell/cell cv cv)))
        n (second ((construct-cell e) n))
        [e->b n] ((p:id [e b]) n)
        [p01 n] (install-compound n k0 [a b] [a b])
        [p12 n] (install-compound n k1 [b c] [b c])]
    {:net n :cells {:a a :b b :c c :e e} :props {:e->b e->b :p01 p01 :p12 p12}}))

;; --- tests ---

(deftest sync-chain-propagates-value
  (with-compiled
    '(let-cell [c0 c1 c2]
       (do (p:id c0 c1)
           (p:id c1 c2)))
    (fn [{:keys [net ctx]}]
      (let [expected (partial 42)
            n (-> net
                  (seed-cell (cell-ref ctx 'c0) 42)
                  (run-prop (prop-ref ctx 0)))]
        (expect-strongest n (cell-ref ctx 'c1) expected "c1 after p01")
        (let [n (run-prop n (prop-ref ctx 1))]
          (expect-strongest n (cell-ref ctx 'c2) expected "c2 after p12"))))))

(deftest stdlib-bi-sync-closure-compound-single
  (testing "compound with bi-sync-closure; seed c0, run once"
    (let [{:keys [net cells props]} (build-stdlib-compound-chain)
          {:keys [c0 c1]} cells
          expected (partial 7)
          n (-> net (seed-cell c0 7) (run-prop (first props)))]
      (expect-strongest n c1 expected "c1 after compound")
      (expect-strongest n c0 expected "c0 after compound (bi-sync)"))))

(deftest stdlib-bi-sync-closure-compound-chain
  (testing "two bi-sync-closure compounds: c0 -> c1 -> c2"
    (let [{:keys [net cells props]} (build-stdlib-compound-chain)
          {:keys [c0 c1 c2]} cells
          [p01 p12] props
          expected (partial 99)
          n (-> net (seed-cell c0 99) (run-prop p01))]
      (expect-strongest n c1 expected "c1 after first compound")
      (let [n (run-prop n p12)]
        (expect-strongest n c2 expected "c2 after second compound")))))

(deftest bi-sync-one-activation
  (with-compiled
    '(let-cell [cA cB]
       (do (p:id cA cB)
           (p:id cB cA)))
    (fn [{:keys [net ctx]}]
      (let [seed (partial 1)
            n (-> net (seed-cell (cell-ref ctx 'cA) 1) (run-prop (prop-ref ctx 0)))]
        (expect-strongest n (cell-ref ctx 'cB) seed "cB after pAB")
        (expect-strongest n (cell-ref ctx 'cA) seed "cA unchanged")))))

(deftest bi-sync-chain-three-cells
  (with-compiled
    '(let-cell [c0 c1 c2]
       (do (p:id c0 c1)
           (p:id c1 c0)
           (p:id c1 c2)
           (p:id c2 c1)))
    (fn [{:keys [net ctx]}]
      (let [expected (partial 99)
            n (-> net
                  (seed-cell (cell-ref ctx 'c0) 99)
                  (run-prop (prop-ref ctx 0)))]
        (expect-strongest n (cell-ref ctx 'c1) expected "c1 after p01")
        (let [n (run-prop n (prop-ref ctx 2))]
          (expect-strongest n (cell-ref ctx 'c2) expected "c2 after p12"))))))

(deftest compound-bi-sync-chain-inject-e-to-b
  (testing "a <-> b <-> c; e -p:id-> b; seed e; run e->b — expect a, b, c all updated"
    (let [{:keys [net cells props]} (build-stdlib-compound-abc-with-inject)
          {:keys [a b c e]} cells
          e->b (:e->b props)
          expected (partial 55)
          n (-> net (seed-cell e 55) (run-prop e->b))]
      (expect-strongest n b expected "b from e")
      (expect-strongest n c expected "c from b (downstream compound)")
      (expect-strongest n a expected "a from b (upstream compound — currently fails)"))))
