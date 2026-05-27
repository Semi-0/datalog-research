(ns propagators-linked-list-access-test
  "Linked-list access and dispatch behavior (p:cons, p:car, p:cdr, c:linked-list).
  Run: clj -M:test propagators-linked-list-access-test"
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell :refer [construct-cell]]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.cells.value :as value]
            [propagators.core :refer [run-tasks]]
            [propagators.datastructures.compound_data :as cd]
            [propagators.datastructures.compound_subnet_state :as state]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn- neighbor-prop-ids [n cell-id]
  (let [g (net/net-graph n)]
    (when (contains? g cell-id)
      (let [node (graph/get-node g cell-id)
            adj (set/union (graph/node-input-ids node) (graph/node-output-ids node))
            env (net/net-env n)]
        (vec (filter #(prop/prop? (get env %)) adj))))))

(defn- enqueue-prop-ids [tasks prop-ids]
  (reduce tq/enqueue tasks prop-ids))

(defn- install-cell [n id content strongest]
  (second ((construct-cell id content strongest) n)))

(defn- install-prop [n installer]
  (second (installer n)))

(defn- install-cell!
  [n tasks id content strongest]
  (let [[cell-id n'] ((construct-cell id content strongest) n)
        tasks' (enqueue-prop-ids tasks (neighbor-prop-ids n' cell-id))]
    [n' tasks']))

(defn- install-prop!
  [n tasks installer]
  (let [result (installer n)
        [prop-id n'] (if (vector? result) result [nil result])
        tasks' (if prop-id (tq/enqueue tasks prop-id) tasks)]
    [n' tasks']))

(defn- seed-cell!
  [n tasks cell-id content strongest]
  (let [n' (net/assoc-net-cell n cell-id (cell/cell content strongest))
        tasks' (enqueue-prop-ids tasks (neighbor-prop-ids n' cell-id))]
    [n' tasks']))

(defn- seed-cells!
  [n tasks cell-value-pairs]
  (reduce (fn [[n' t] [id v]]
            (seed-cell! n' t id v v))
          [n tasks]
          cell-value-pairs))

(defn- run-from
  ([n seed-ids] (run-from n tq/empty-queue seed-ids))
  ([n pending seed-ids]
   (let [g (net/net-graph n)
         tasks (tq/merge-queues pending (tq/into-queue (pop-inputs seed-ids g)))]
     (run-tasks tasks n))))

(defn- build-nested-with-cons
  "Each layer: (p:cons head_i tail_i coll_i); last tail is a sentinel nothing cell."
  [layers]
  (let [sentinel (new-node-id)
        ids (vec (repeatedly (+ (* 2 layers) 1) new-node-id))
        coll-ids (mapv #(nth ids (+ (* 2 %) 1)) (range layers))
        head-ids (mapv #(nth ids (* 2 %)) (range layers))
        n (-> net/empty-net
              (install-cell sentinel value/nothing value/nothing)
              (as-> n' (reduce (fn [n id] (install-cell n id value/nothing value/nothing))
                               n'
                               ids)))
        net (reduce
             (fn [n i]
               (let [h (head-ids i)
                     c (coll-ids i)
                     t (if (< i (dec layers)) (coll-ids (inc i)) sentinel)]
                 (install-prop n (cd/p:cons h t c))))
             n
             (range layers))]
    {:net net :head-ids head-ids :coll-ids coll-ids :sentinel sentinel}))

(defn- build-nested-linked-list [layers]
  (let [ids (vec (repeatedly (+ (* 2 layers) 1) new-node-id))
        coll-ids (mapv #(nth ids (+ (* 2 %) 1)) (range layers))
        head-ids (mapv #(nth ids (* 2 %)) (range layers))
        n (reduce (fn [net id] (install-cell net id value/nothing value/nothing))
                  net/empty-net
                  ids)]
    (loop [layer 0 n n]
      (if (= layer layers)
        {:net n :head-ids head-ids :coll-ids coll-ids :layers layers}
        (let [h (head-ids layer)
              c (coll-ids layer)
              t (when (< layer (dec layers)) (coll-ids (inc layer)))
              n (-> n
                    (install-prop (cd/p:car h c))
                    (install-prop (cd/c:linked-list c)))]
          (recur (inc layer)
                 (if t (install-prop n (cd/p:cdr t c)) n)))))))

(defn- lisp-car-cdr-cdr [head-ids]
  (nth head-ids 2))

(defn- build-three-layer-cons-with-accessor []
  (let [{:keys [net head-ids coll-ids]} (build-nested-with-cons 3)
        [coll0 coll1 coll2] coll-ids
        [head0 head1 head2] head-ids
        out (new-node-id)
        [n tasks] (let [[n t] (install-cell! net tq/empty-queue out value/nothing value/nothing)
                        [n t] (install-prop! n t (cd/p:cdr coll1 coll0))
                        [n t] (install-prop! n t (cd/p:cdr coll2 coll1))
                        [n t] (install-prop! n t (cd/p:car out coll2))]
                    [n t])]
    {:net n
     :tasks tasks
     :coll0 coll0 :coll1 coll1 :coll2 coll2
     :head0 head0 :head1 head1 :head2 head2
     :out out}))

(deftest p-cons-five-then-access-via-cons-wired-car-cdr
  (testing "p:cons x5 build; access index 2 via head2 (p:car/p:cdr already on that pair)"
    (let [{:keys [net head-ids coll-ids]} (build-nested-with-cons 5)
          head2 (head-ids 2)
          n' (-> net
                 (net/assoc-net-cell head2 (cell/cell 30 30))
                 (run-from [head2]))]
      (is (= 30 (cell/cell-strongest (net/network-env-lookup n' head2))))
      (is (state/compound-subnet-state?
           (cell/cell-strongest (net/network-env-lookup n' (coll-ids 2))))))))

(deftest p-cons-five-access-from-coll0-only-does-not-reach-head2
  (testing "p:cons x5; seed only head0, run coll0 — deep head2 not reached without chain"
    (let [{:keys [net head-ids coll-ids]} (build-nested-with-cons 5)
          coll0 (coll-ids 0)
          head0 (head-ids 0)
          head2 (head-ids 2)
          n (-> net (net/assoc-net-cell head0 (cell/cell 10 10)))
          n' (run-from n [coll0])]
      (is (= 10 (cell/cell-strongest (net/network-env-lookup n' head0))))
      (is (value/nothing? (cell/cell-strongest (net/network-env-lookup n' head2)))
          "coll0 alone must not propagate to deep head2 via chained dispatch"))))

(deftest p-cons-five-chain-from-coll0-reaches-head2-when-seeded
  (testing "positive control: seed head2 only, run coll0 — chained c:linked-list may export to head2"
    (let [{:keys [net head-ids coll-ids]} (build-nested-with-cons 5)
          coll0 (coll-ids 0)
          head2 (head-ids 2)
          n (-> net (net/assoc-net-cell head2 (cell/cell 30 30)))
          n' (run-from n [coll0])]
      (is (= 30 (cell/cell-strongest (net/network-env-lookup n' head2)))
          "hypothesis: coll0 linked-list chain dispatches nested head2 value"))))

;; (p:cdr coll1 coll0) (p:cdr coll2 coll1) (p:car out coll2) + 3x p:cons → (car (cdr (cdr coll0))) at out.

(deftest three-layer-cons-accessor-three-heads-seeded-run-coll0-reaches-out
  (testing "(car (cdr (cdr coll0))): seed head0/head1/head2, run coll0 — out gets index-2 value 30"
    (let [{:keys [net tasks coll0 head0 head1 head2 out]}
          (build-three-layer-cons-with-accessor)
          [n tasks] (seed-cells! net tasks [[head0 10] [head1 20] [head2 30]])
          n' (run-from n tasks [coll0])]
      (is (= 10 (cell/cell-strongest (net/network-env-lookup n' head0))))
      (is (= 30 (cell/cell-strongest (net/network-env-lookup n' out)))))))

(deftest three-layer-cons-accessor-head2-seeded-run-coll0-reaches-out
  (testing "seed head2=30 only, run coll0 — accessor chain exports to out (not only head2)"
    (let [{:keys [net tasks coll0 head2 out]}
          (build-three-layer-cons-with-accessor)
          [n tasks] (seed-cell! net tasks head2 30 30)
          n' (run-from n tasks [coll0])]
      (is (= 30 (cell/cell-strongest (net/network-env-lookup n' out)))))))

(deftest p-cons-five-extra-car-cdr-from-coll0-not-access
  (testing "after p:cons x5, extra p:car/p:cdr on coll0 cannot route values to a new out cell"
    (let [{:keys [net head-ids coll-ids]} (build-nested-with-cons 5)
          coll0 (coll-ids 0)
          out (new-node-id)
          n (-> net
                (install-cell out value/nothing value/nothing)
                (install-prop (cd/p:car out coll0))
                (install-prop (cd/p:cdr out coll0)))
          n' (-> n
                 (net/assoc-net-cell (head-ids 2) (cell/cell 88 88))
                 (run-from [(head-ids 2)]))]
      (is (= 88 (cell/cell-strongest (net/network-env-lookup n' (head-ids 2)))))
      (is (value/nothing? (cell/cell-strongest (net/network-env-lookup n' out)))))))

(deftest five-element-list-propagation-reaches-index-two
  (testing "values on head cells are reachable; index 2 gets 30"
    (let [{:keys [net head-ids]} (build-nested-linked-list 5)
          values [10 20 30 40 50]
          n (reduce (fn [n [h v]] (net/assoc-net-cell n h (cell/cell v v)))
                    net
                    (map vector head-ids values))
          n' (run-tasks (tq/into-queue (pop-inputs head-ids (net/net-graph n))) n)
          target (lisp-car-cdr-cdr head-ids)]
      (is (= 30 (cell/cell-strongest (net/network-env-lookup n' target))))
      (is (= 10 (cell/cell-strongest (net/network-env-lookup n' (head-ids 0)))))
      (is (= 50 (cell/cell-strongest (net/network-env-lookup n' (head-ids 4))))))))

(deftest five-layer-p-cons-matches-manual-wiring
  (testing "p:cons per layer = p:car + p:cdr + c:linked-list; index 2 still head2"
    (let [{:keys [net head-ids coll-ids]} (build-nested-with-cons 5)
          values [10 20 30 40 50]
          n (reduce (fn [n [h v]] (net/assoc-net-cell n h (cell/cell v v)))
                    net
                    (map vector head-ids values))
          n' (run-tasks (tq/into-queue (pop-inputs head-ids (net/net-graph n))) n)
          target (lisp-car-cdr-cdr head-ids)]
      (is (state/compound-subnet-state? (cell/cell-content (net/network-env-lookup n' (coll-ids 0)))))
      (is (= 30 (cell/cell-strongest (net/network-env-lookup n' target)))))))

(deftest car-cdr-cdr-dispatch-via-coll2-not-coll0
  (testing "(car (cdr (cdr coll0))) = head2: dispatch runs on coll2's c:linked-list, not coll0's"
    (let [{:keys [net head-ids coll-ids]} (build-nested-with-cons 5)
          head0 (head-ids 0)
          head2 (head-ids 2)
          n' (-> net
                 (net/assoc-net-cell head2 (cell/cell 42 42))
                 (run-from [head2]))]
      (is (= 42 (cell/cell-strongest (net/network-env-lookup n' head2))))
      (is (value/nothing? (cell/cell-strongest (net/network-env-lookup n' head0)))
          "coll0's dispatcher does not forward index-2 value to head0")
      (is (state/compound-subnet-state?
           (cell/cell-strongest (net/network-env-lookup n' (coll-ids 2))))
          "coll2 strongest is structural; c:linked-list runs subnet + dispatch"))))

(deftest c-linked-list-dispatches-to-element-not-nested-collection
  (testing "element updates keep tail collection ids out of out-ids"
    (let [{:keys [net head-ids coll-ids]} (build-nested-linked-list 5)
          coll2 (nth coll-ids 2)
          n (-> net (net/assoc-net-cell (head-ids 2) (cell/cell 77 77)))
          n' (run-from n [(head-ids 2)])
          content (cell/cell-content (net/network-env-lookup n' coll2))
          _subnet (state/state-subnet content)]
      (is (contains? (state/state-out-ids content) (head-ids 2)))
      (is (not (contains? (state/state-out-ids content) (nth coll-ids 3)))
          "tail collection id is not in out-ids dispatch set for element updates"))))
