(ns propagators-network-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :refer [cell-value-equal?]]
            [propagators.closure :refer [compound-propagator]]
            [propagators.compile :refer [cell-ref compile-net prop-ref]]
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
  (merge/cell-strongest (net/env-get env cell-id)))

(defn- seed-cell [n cell-id v]
  (net/assoc-net-cell n cell-id (cell/cell v v)))

(defn- run-prop [n prop-id]
  (let [node (get-node (net/net-graph n) prop-id)]
    (run-tasks (tq/enqueue tq/empty-queue node) n)))

(defn- run-compound-chain [n prop-ids]
  (reduce run-prop n prop-ids))

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
                             (cell/cell? v) {:kind :cell :strongest (merge/cell-strongest v)}
                             (prop? v) {:kind :propagator}
                             :else {:kind :unknown})])
                     env))}))

(defn- expect-strongest [n cell-id expected label]
  (is (cell-value-equal? expected (strongest (net/net-env n) cell-id))
      (str label " " (dump-net n))))

;; --- runtime network builders (net-let) ---

(defn- install-compound [n closure-in closure-out inputs outputs]
  (let [[prop-id n'] ((compound-propagator closure-in closure-out inputs outputs) n)]
    [prop-id n']))

(defn- build-stdlib-compound-chain-n
  "n cells, (n-1) bi-sync compounds; boundary [ci cj] in/out; closure-in + closure-out per hop."
  [chain-len]
  (when (< chain-len 2)
    (throw (ex-info "chain-len must be >= 2" {:chain-len chain-len})))
  (let [cells (vec (repeatedly chain-len new-node-id))
        closures-in (vec (repeatedly (dec chain-len) new-node-id))
        closures-out (vec (repeatedly (dec chain-len) new-node-id))
        cv bi-sync-closure
        n (reduce (fn [net id] (second ((construct-cell id) net)))
                  net/empty-net
                  (into cells (concat closures-in closures-out)))
        n (reduce #(net/assoc-net-cell %1 %2 (cell/cell cv cv))
                  n
                  (concat closures-in closures-out))
        [n props] (reduce
                   (fn [[n props] i]
                     (let [left (cells i)
                           right (cells (inc i))
                           k-in (closures-in i)
                           k-out (closures-out i)
                           [p n'] (install-compound n k-in k-out [left right] [left right])]
                       [n' (conj props p)]))
                   [n []]
                   (range (dec chain-len)))]
    {:net n :cells cells :props props}))

(defn- build-stdlib-compound-chain []
  (build-stdlib-compound-chain-n 3))

(defn- build-stdlib-compound-chain-n-with-inject
  [chain-len inject-idx]
  (when-not (<= 0 inject-idx (dec chain-len))
    (throw (ex-info "inject-idx out of range" {:chain-len chain-len :inject-idx inject-idx})))
  (let [{:keys [net cells props]} (build-stdlib-compound-chain-n chain-len)
        mid (nth cells inject-idx)
        e (new-node-id)
        n (second ((construct-cell e) net))
        [e->mid n] ((p:id [e mid]) n)]
    {:net n
     :cells cells
     :inject-idx inject-idx
     :mid mid
     :e e
     :e->mid e->mid
     :props props}))

(defn- build-stdlib-compound-abc-with-inject []
  (let [{:keys [net cells mid e e->mid props inject-idx]}
        (build-stdlib-compound-chain-n-with-inject 3 1)
        [a b c] cells]
    {:net net
     :cells {:a a :b b :c c :e e}
     :props {:e->b e->mid :chain props :inject-idx inject-idx :mid mid}}))

(defn- assert-compound-chain-from-head [chain-len seed-val]
  (let [{:keys [net cells props]} (build-stdlib-compound-chain-n chain-len)
        expected seed-val
        n (-> net (seed-cell (first cells) seed-val) (run-compound-chain props))]
    (doseq [[i c] (map-indexed vector cells)]
      (expect-strongest n c expected (str "cell " i " chain-len " chain-len)))))

;; --- tests ---

(deftest sync-chain-propagates-value
  (with-compiled
    '(let-cell [c0 c1 c2]
       (do (p:id c0 c1)
           (p:id c1 c2)))
    (fn [{:keys [net ctx]}]
      (let [expected 42
            n (-> net
                  (seed-cell (cell-ref ctx 'c0) 42)
                  (run-prop (prop-ref ctx 0)))]
        (expect-strongest n (cell-ref ctx 'c1) expected "c1 after p01")
        (let [n (run-prop n (prop-ref ctx 1))]
          (expect-strongest n (cell-ref ctx 'c2) expected "c2 after p12"))))))

(deftest stdlib-bi-sync-closure-compound-single
  (testing "compound with bi-sync-closure; seed c0, run once"
    (let [{:keys [net cells props]} (build-stdlib-compound-chain)
          [c0 c1] cells
          expected 7
          n (-> net (seed-cell c0 7) (run-prop (first props)))]
      (expect-strongest n c1 expected "c1 after compound")
      (expect-strongest n c0 expected "c0 after compound (bi-sync)"))))

(deftest stdlib-bi-sync-closure-compound-chain
  (testing "two bi-sync-closure compounds: c0 -> c1 -> c2"
    (let [{:keys [net cells props]} (build-stdlib-compound-chain)
          [c0 c1 c2] cells
          [p01 p12] props
          expected 99
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
      (let [seed 1
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
      (let [expected 99
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
          expected 55
          n (-> net (seed-cell e 55) (run-prop e->b))]
      (expect-strongest n b expected "b from e")
      (expect-strongest n c expected "c from b (downstream compound)")
      (expect-strongest n a expected "a from b (upstream compound)"))))

(deftest stdlib-bi-sync-closure-compound-chain-4
  (testing "4 cells, 3 bi-sync compounds; seed head, run all props"
    (assert-compound-chain-from-head 4 11)))

(deftest stdlib-bi-sync-closure-compound-chain-10
  (testing "10 cells, 9 bi-sync compounds; seed head, run all props"
    (assert-compound-chain-from-head 10 42)))

(deftest compound-bi-sync-chain-10-inject-middle
  (testing "10-cell chain; e -p:id-> c5; seed e; run e->mid once — all cells updated"
    (let [chain-len 10
          inject-idx (quot chain-len 2)
          {:keys [net cells mid e e->mid]} (build-stdlib-compound-chain-n-with-inject
                                            chain-len inject-idx)
          expected 77
          n (-> net (seed-cell e 77) (run-prop e->mid))]
      (is (= 5 inject-idx) "middle index for len 10")
      (expect-strongest n mid expected "middle from e")
      (doseq [[i c] (map-indexed vector cells)]
        (expect-strongest n c expected (str "cell " i " after mid inject"))))))
