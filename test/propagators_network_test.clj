(ns propagators-network-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell :refer [construct-cell]]
            [propagators.cells.diff :as diff]
            [propagators.cells.value :refer [cell-value-equal?]]
            [propagators.compile :as compile]
            [propagators.compile :refer [cell-ref compile-net prop-ref]]
            [propagators.graph :as graph :refer [node-input-ids node-output-ids]]
            [propagators.message :as m]
            [propagators.propagator :refer [compound-propagator prop?]]
            [propagators.core :refer [run-tasks]]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.stdlib :refer [bi-sync-closure p:id]]))

;; --- harness ---

(defn- net-of-ctx [ctx]
  (net/net (:graph ctx) (:env ctx)))

(defn- with-compiled [expr f]
  (let [ctx (compile-net expr)]
    (f {:net (net-of-ctx ctx) :ctx ctx})))

(defn- strongest [env cell-id]
  (cell/cell-strongest (net/env-get env cell-id)))

(defn- seed-cell [n cell-id v]
  (net/assoc-net-cell n cell-id (cell/cell v v)))

(defn- run-prop [n prop-id]
  (run-tasks (tq/enqueue tq/empty-queue prop-id) n))

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
                             (cell/cell? v) {:kind :cell :strongest (cell/cell-strongest v)}
                             (prop? v) {:kind :propagator}
                             :else {:kind :unknown})])
                     env))}))

(defn- expect-strongest [n cell-id expected label]
  (is (cell-value-equal? expected (strongest (net/net-env n) cell-id))
      (str label " " (dump-net n))))

;; --- runtime network builders (net-let) ---

(defn- install-compound [n closure-in inputs outputs]
  (let [[prop-id n'] ((compound-propagator closure-in inputs outputs) n)]
    [prop-id n']))

(defn- build-stdlib-compound-chain-n
  "n cells, (n-1) bi-sync compounds; boundary [ci cj] in/out; closure-in per hop."
  [chain-len]
  (when (< chain-len 2)
    (throw (ex-info "chain-len must be >= 2" {:chain-len chain-len})))
  (let [cells (vec (repeatedly chain-len new-node-id))
        closures-in (vec (repeatedly (dec chain-len) new-node-id))
        cv bi-sync-closure
        n (reduce (fn [net id] (second ((construct-cell id) net)))
                  net/empty-net
                  (into cells closures-in))
        n (reduce #(net/assoc-net-cell %1 %2 (cell/cell cv cv))
                  n
                  closures-in)
        [n props] (reduce
                   (fn [[n props] i]
                     (let [left (cells i)
                           right (cells (inc i))
                           k-in (closures-in i)
                           [p n'] (install-compound n k-in [left right] [left right])]
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
        [e->mid n] ((p:id e mid) n)]
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

(deftest compiler-self-evaluating-values
  (is (= 42 (:value (compile/eval-net 42))))
  (is (= "x" (:value (compile/eval-net "x"))))
  (is (= :provenance (:value (compile/eval-net :provenance))))
  (is (= true (:value (compile/eval-net true))))
  (is (= #{:a} (:value (compile/eval-net #{:a}))))
  (is (= [:a :b] (:value (compile/eval-net [:a :b])))))

(deftest compiler-symbol-auto-creates-cell
  (let [ctx (compile/eval-net 'a)
        a (compile/cell-ref ctx 'a)]
    (is a)
    (is (= a (:value ctx)))
    (is (cell/cell? (net/network-lookup-cell (:net ctx) a)))))

(deftest compiler-repeated-symbol-reuses-cell
  (let [ctx (compile/eval-net
             '(let-cell [a]
                a))
        a1 (compile/cell-ref ctx 'a)]
    (is (= a1 (:value ctx)))))

(deftest compiler-application-auto-creates-args-and-collects-props
  (let [ctx (compile/eval-net '(p:id a b))]
    (is (compile/cell-ref ctx 'a))
    (is (compile/cell-ref ctx 'b))
    (is (= 1 (count (:props ctx))))))

(deftest compiler-let-cell-variadic-body
  (let [ctx (compile/compile-net
             '(let-cell [a b c]
                (p:id a b)
                (p:id b c)))]
    (is (= 3 (count (:cells ctx))))
    (is (= 2 (count (:props ctx))))))

(deftest compiler-keyword-arg-self-evaluates
  (let [seen (atom nil)
        installer (fn [layer a b]
                    (reset! seen [layer a b])
                    (fn [n]
                      [(new-node-id) n]))
        ctx (compile/eval-net
             net/empty-net
             {'fake/layer installer}
             '(fake/layer :provenance a b))]
    (is (= :provenance (first @seen)))
    (is (= 1 (count (:props ctx))))))

(deftest compiler-seed-form-creates-and-seeds-cell
  (let [ctx (compile/eval-net '(seed a 42))
        a (compile/cell-ref ctx 'a)]
    (is (= a (:value ctx)))
    (is (= 42 (cell/cell-strongest (net/network-lookup-cell (:net ctx) a))))))

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

(deftest verify-diff-cells-messages-target-real-cells
  (testing "stdlib compound chain (same builder as inject tests): diff-cells → real left/right only; 0–2 msgs; real cells have downstream props"
    (let [chain-len 10
          inject-idx (quot chain-len 2)
          {:keys [net cells props e e->mid]}
          (build-stdlib-compound-chain-n-with-inject chain-len inject-idx)
          real-cells (set cells)
          props-set (set props)
          diff-log (atom [])
          orig-diff diff/diff-internal-output-cells
          recording-diff
          (fn [network-from network-to external-outputs]
            (let [msgs (vec (orig-diff network-from network-to external-outputs))
                  avatar-outs (vals (:avatars-out (net/net-dict-or-empty network-from)))]
              (swap! diff-log conj {:external-outputs (vec external-outputs)
                                    :avatar-outs (vec avatar-outs)
                                    :targets (mapv m/message-id msgs)
                                    :count (count msgs)})
              msgs))
          out-edges
          (fn [n id]
            (graph/node-output-ids (graph/get-node (net/net-graph n) id)))]
      ;; --- static graph (test builder wiring) ---
      (doseq [c real-cells]
        (let [outs (out-edges net c)]
          (is (pos? (count outs))
              (str "real cell " c " must have downstream edges, got " outs))
          (is (boolean (some #(or (contains? props-set %)
                                   (= % e->mid))
                            outs))
              (str "real cell downstream must include a compound or inject prop: " outs))))
      (doseq [c real-cells]
        (is (pos? (count (out-edges net c)))
            (str "real boundary cell keeps downstream edges: " c)))
      ;; --- run middle inject (compound-bi-sync-chain-10-inject-middle) ---
      (with-redefs [diff/diff-internal-output-cells recording-diff]
        (let [expected 77
              n (-> net (seed-cell e expected) (run-prop e->mid))
              log @diff-log
              total-msgs (reduce + 0 (map :count log))
              counts (frequencies (map :count log))
              avatar-ids (set (mapcat :avatar-outs log))
              bad-targets
              (filter (fn [t]
                        (or (not (contains? real-cells t))
                            (contains? avatar-ids t)))
                      (mapcat :targets log))]
          (is (pos? (count log)) "diff-cells should run during propagation")
          (is (every? #(<= 0 % 2) (map :count log))
              (str "each diff call emits 0–2 messages, frequencies: " counts))
          (is (empty? bad-targets)
              (str "every diff message target must be a real boundary cell, not an avatar; bad: "
                   (vec bad-targets)))
          (is (pos? total-msgs)
              (str "cascade should produce at least one diff message, total=" total-msgs))
          (doseq [[i c] (map-indexed vector cells)]
            (expect-strongest n c expected (str "cell " i " after mid inject")))
          ;; print empirical summary for inspection
          (println "\n=== diff-cells verification (chain-len=10, inject mid) ===")
          (println "  diff-cells invocations:" (count log))
          (println "  messages per call (count -> #calls):" counts)
          (println "  total diff messages:" total-msgs)
          (println "  sample targets (first 5 calls):" (vec (take 5 (map :targets log)))))))))

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
