(ns propagators-linked-list-access-test
  "Verify whether (car (cdr (cdr collection))) is reachable using only p:car / p:cdr.
  Run: clj -M:test propagators-linked-list-access-test"
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell :refer [construct-cell]]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.cells.value :as value]
            [propagators.core :refer [run-tasks]]
            [propagators.datastructures.compound_data :as cd]
            [propagators.datastructures.compound_subnet_state :as state]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]))

(defn- install-cell [n id content strongest]
  (second ((construct-cell id content strongest) n)))

(defn- install-prop [n installer]
  (second (installer n)))

(defn- run-from [n seed-ids]
  (let [g (net/net-graph n)
        tasks (pop-inputs seed-ids g)]
    (run-tasks tasks n)))

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
                               ids)))]
    {:net (reduce
           (fn [n i]
             (let [h (head-ids i)
                   c (coll-ids i)
                   t (if (< i (dec layers)) (coll-ids (inc i)) sentinel)]
               (install-prop n (cd/p:cons h t c))))
           n
           (range layers))
     :head-ids head-ids
     :coll-ids coll-ids
     :sentinel sentinel}))

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

;; Structural walk matching Lisp (car (cdr ... (cdr coll)))) — inspects wiring, not new propagators.
(defn- lisp-nth-coll [coll-ids n]
  (nth coll-ids n))

(defn- lisp-nth-head [head-ids n]
  (nth head-ids n))

;; Lisp: (car (cdr (cdr coll0))) => element at index 2 for a 5-cell list.
(defn- lisp-car-cdr-cdr [head-ids]
  (lisp-nth-head head-ids 2))

(deftest dispatch-without-filter-still-does-not-walk-to-inner-heads
  (testing "dispatch from coll0 alone does not equate head0@coll0 to head2"
    (let [{:keys [net head-ids coll-ids]} (build-nested-with-cons 5)
          coll0 (coll-ids 0)
          head0 (head-ids 0)
          head2 (head-ids 2)
          n (-> net (net/assoc-net-cell head0 (cell/cell 10 10)))
          n' (run-from n [coll0])]
      (is (= 10 (cell/cell-strongest (net/network-env-lookup n' head0))))
      (is (value/nothing? (cell/cell-strongest (net/network-env-lookup n' head2)))))))

(deftest dispatch-with-filter-keeps-nested-collections-local
  (testing "default dispatch remains local when only coll0 is seeded"
    (let [{:keys [net head-ids coll-ids]} (build-nested-with-cons 5)
          coll0 (coll-ids 0)
          head0 (head-ids 0)
          head2 (head-ids 2)
          n (-> net (net/assoc-net-cell head0 (cell/cell 10 10)))
          n' (run-from n [coll0])]
      (is (= 10 (cell/cell-strongest (net/network-env-lookup n' head0))))
      (is (value/nothing? (cell/cell-strongest (net/network-env-lookup n' head2)))))))

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

(deftest p-cons-five-local-control-head2-run-reaches-head2
  (testing "positive control: seed head2 and run from head2 — local layer dispatch"
    (let [{:keys [net head-ids]} (build-nested-with-cons 5)
          head2 (head-ids 2)
          n' (-> net (net/assoc-net-cell head2 (cell/cell 30 30)) (run-from [head2]))]
      (is (= 30 (cell/cell-strongest (net/network-env-lookup n' head2)))))))

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

(deftest five-element-list-structure-matches-car-cdr-cdr
  (testing "5-element nested encoding: (car (cdr (cdr coll0))) is head2"
    (let [{:keys [coll-ids head-ids]} (build-nested-linked-list 5)
          coll2 (lisp-nth-coll coll-ids 2)
          expected-head (lisp-car-cdr-cdr head-ids)]
      (is (= coll2 (lisp-nth-coll coll-ids 2)))
      (is (= expected-head (lisp-nth-head head-ids 2))))))

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

(deftest p-car-p-cdr-alone-cannot-compose-as-accessors
  (testing "p:car / p:cdr are writers (elem -> collection); no collection -> elem read path"
    (let [{:keys [net head-ids coll-ids]} (build-nested-linked-list 5)
          coll0 (coll-ids 0)
          ;; Attempt: wire only extra p:car/p:cdr from coll0 outward to a fresh result cell.
          result (new-node-id)
          n (-> net
                (install-cell result value/nothing value/nothing)
                ;; Wrong direction for access: these still push elem -> collection, not navigate.
                (install-prop (cd/p:car result coll0))
                (install-prop (cd/p:cdr result coll0)))
          n' (-> n
                 (net/assoc-net-cell (head-ids 2) (cell/cell 99 99))
                 (run-from [(head-ids 2)]))]
      (is (value/nothing? (cell/cell-strongest (net/network-env-lookup n' result)))
          "result cell is not (car (cdr (cdr coll0))) — p:car/p:cdr do not read the list"))))

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

(deftest p-cons-chain-does-not-route-coll0-to-head2
  (testing "even with p:cons on every layer, no cell at coll0 reads head2 without knowing head2"
    (let [{:keys [net head-ids coll-ids]} (build-nested-with-cons 5)
          coll0 (coll-ids 0)
          observer (new-node-id)
          n (-> net (install-cell observer value/nothing value/nothing))
          n' (-> n
                 (net/assoc-net-cell (head-ids 2) (cell/cell 88 88))
                 (run-from [(head-ids 2)]))]
      (is (= 88 (cell/cell-strongest (net/network-env-lookup n' (head-ids 2)))))
      (is (value/nothing? (cell/cell-strongest (net/network-env-lookup n' observer))))
      (is (not= (cell/cell-strongest (net/network-env-lookup n' coll0)) 88)
          "coll0 strongest is compound subnet state, not element 2"))))

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
