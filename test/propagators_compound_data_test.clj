(ns propagators-compound-data-test
  "Linked-list compound_data: p:car, p:cdr, c:linked-list.
  Run: clj -M:test propagators-compound-data-test"
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.avatar :as avatar]
            [propagators.cells.cell :as cell :refer [construct-cell]]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.cells.value :as value]
            [propagators.core :refer [run-tasks]]
            [propagators.datastructures.compound_data :as cd]
            [propagators.datastructures.compound_strongest_result :as strongest]
            [propagators.datastructures.compound_subnet_state :as state]
            [propagators.datastructures.compound_subnet :as subnet]
            [propagators.datastructures.compound_update :as update]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn- install-cell [n id content strongest]
  (second ((construct-cell id content strongest) n)))

(defn- install-prop [n installer]
  (second (installer n)))

(defn- run-from [n seed-ids]
  (let [g (net/net-graph n)
        tasks (pop-inputs seed-ids g)]
    (run-tasks tasks n)))

(defn- build-flat-linked-list []
  (let [head (new-node-id)
        tail (new-node-id)
        coll (new-node-id)
        n (-> net/empty-net
              (install-cell head value/nothing value/nothing)
              (install-cell tail value/nothing value/nothing)
              (install-cell coll value/nothing value/nothing))]
    {:net (-> n
              (install-prop (cd/p:car head coll))
              (install-prop (cd/p:cdr tail coll))
              (install-prop (cd/c:linked-list coll)))
     :head head
     :tail tail
     :coll coll}))

(defn- build-nested-linked-list [layers]
  (let [ids (vec (repeatedly (+ (* 2 layers) 1) new-node-id))
        coll-ids (mapv #(nth ids (+ (* 2 %) 1)) (range layers))
        head-ids (mapv #(nth ids (* 2 %)) (range layers))
        tail-ids (mapv #(nth ids (+ (* 2 %) 2)) (range (dec layers)))
        n (reduce (fn [net id] (install-cell net id value/nothing value/nothing))
                  net/empty-net
                  ids)]
    (loop [layer 0
           n n]
      (if (= layer layers)
        {:net n :head-ids head-ids :coll-ids coll-ids :tail-ids tail-ids :layers layers}
        (let [h (head-ids layer)
              c (coll-ids layer)
              t (when (< layer (dec layers)) (coll-ids (inc layer)))
              n (-> n
                    (install-prop (cd/p:car h c))
                    (install-prop (cd/c:linked-list c)))]
          (recur (inc layer)
                 (if t
                   (install-prop n (cd/p:cdr t c))
                   n)))))))

(deftest car-writes-head-update-to-collection
  (testing "p:car merges head id into collection content"
    (let [{:keys [net head coll]} (build-flat-linked-list)
          n (-> net (net/assoc-net-cell head (cell/cell 10 10)))
          n' (run-from n [head])
          content (cell/cell-content (net/network-env-lookup n' coll))]
      (is (state/compound-subnet-state? content))
      (is (contains? (state/state-out-ids content) head)))))

(deftest cdr-writes-tail-update-to-collection
  (testing "p:cdr merges tail id into collection content"
    (let [{:keys [net tail coll]} (build-flat-linked-list)
          n (-> net (net/assoc-net-cell tail (cell/cell 20 20)))
          n' (run-from n [tail])
          content (cell/cell-content (net/network-env-lookup n' coll))]
      (is (state/compound-subnet-state? content))
      (is (contains? (state/state-out-ids content) tail)))))

(deftest car-does-not-read-collection
  (testing "collection stays nothing until p:car fires"
    (let [{:keys [net coll]} (build-flat-linked-list)]
      (is (value/nothing? (cell/cell-strongest (net/network-env-lookup net coll)))))))

(deftest car-emits-when-element-nothing
  (testing "p:car still writes {:head id} when element is nothing"
    (let [{:keys [net head coll]} (build-flat-linked-list)
          n' (run-from net [head])
          content (cell/cell-content (net/network-env-lookup n' coll))]
      (is (value/nothing? (cell/cell-strongest (net/network-env-lookup net head))))
      (is (state/compound-subnet-state? content))
      (is (contains? (state/state-out-ids content) head)))))

(deftest linked-list-dispatches-to-updated-outer-ids
  (testing "flat list: head and tail wired; propagation updates element strongests"
    (let [{:keys [net head tail coll]} (build-flat-linked-list)
          n (-> net
                (net/assoc-net-cell head (cell/cell 10 10))
                (net/assoc-net-cell tail (cell/cell 20 20)))
          n' (run-tasks (tq/into-queue (pop-inputs [head tail] (net/net-graph n))) n)
          strongest (cell/cell-strongest (net/network-env-lookup n' coll))]
      (is (strongest/compound-subnet-continuation? strongest))
      (is (= 10 (cell/cell-strongest (net/network-env-lookup n' head))))
      (is (= 20 (cell/cell-strongest (net/network-env-lookup n' tail)))))))

(deftest sync-avatar-cell-syncs-existing-avatar
  (testing "re-merge syncs avatar from parent when id already in dict"
    (let [{:keys [net head coll]} (build-flat-linked-list)
          n (-> net (net/assoc-net-cell head (cell/cell 10 10)))
          n' (run-from n [head])
          content (cell/cell-content (net/network-env-lookup n' coll))
          n2 (-> n' (net/assoc-net-cell head (cell/cell 99 99)))
          update (update/compound-update {:head head})
          state' (subnet/merge-compound-data content update n2)
          subnet' (state/state-subnet state')
          avatar-strongest (avatar/avatar-strongest subnet' head)]
      (is (= 99 avatar-strongest)))))

(deftest nested-linked-list-two-layers
  (testing "outer tail slot points at inner collection cell"
    (let [{:keys [net head-ids coll-ids]}
          (build-nested-linked-list 2)
          head0 (head-ids 0)
          head1 (head-ids 1)
          coll0 (coll-ids 0)
          coll1 (coll-ids 1)
          n (-> net
                (net/assoc-net-cell head1 (cell/cell 11 11))
                (net/assoc-net-cell head0 (cell/cell 10 10)))
          n' (-> n (run-from [head1]) (run-from [head0]))]
      (is (state/compound-subnet-state? (cell/cell-content (net/network-env-lookup n' coll1))))
      (is (state/compound-subnet-state? (cell/cell-content (net/network-env-lookup n' coll0))))
      (is (= 11 (cell/cell-strongest (net/network-env-lookup n' head1))))
      (is (= 10 (cell/cell-strongest (net/network-env-lookup n' head0)))))))

(deftest nested-linked-list-three-layers
  (testing "three nested collection cells"
    (let [{:keys [net head-ids coll-ids]}
          (build-nested-linked-list 3)
          h0 (head-ids 0)
          h2 (head-ids 2)
          c2 (coll-ids 2)
          n (-> net
                (net/assoc-net-cell h2 (cell/cell 3 3))
                (net/assoc-net-cell h0 (cell/cell 1 1)))
          n' (-> n (run-from [h2]) (run-from [h0]))]
      (is (state/compound-subnet-state? (cell/cell-content (net/network-env-lookup n' c2))))
      (is (= 3 (cell/cell-strongest (net/network-env-lookup n' h2))))
      (is (= 1 (cell/cell-strongest (net/network-env-lookup n' h0)))))))

(deftest nested-linked-list-five-layers-wiring-pattern
  (testing "5 cons cells: (p:cdr coll_{i+1} coll_i), (p:car h_i coll_i), (c:linked-list coll_i)"
    (let [{:keys [net head-ids coll-ids]} (build-nested-linked-list 5)
          values [10 20 30 40 50]
          n (reduce (fn [n [h v]] (net/assoc-net-cell n h (cell/cell v v)))
                    net
                    (map vector head-ids values))
          n' (run-tasks (tq/into-queue (pop-inputs head-ids (net/net-graph n))) n)]
      (doseq [c coll-ids]
        (is (state/compound-subnet-state?
             (cell/cell-content (net/network-env-lookup n' c)))
            (str "collection " c " has subnet content")))
      (doseq [[h v] (map vector head-ids values)]
        (is (= v (cell/cell-strongest (net/network-env-lookup n' h)))
            (str "head " h " keeps value " v)))
      (let [coll0 (coll-ids 0)
            coll2 (coll-ids 2)
            state0 (cell/cell-content (net/network-env-lookup n' coll0))
            state2 (cell/cell-content (net/network-env-lookup n' coll2))
            _subnet0 (state/state-subnet state0)
            out0 (state/state-out-ids state0)
            _subnet2 (state/state-subnet state2)
            out2 (state/state-out-ids state2)]
        (is (contains? out0 (head-ids 0)))
        (is (contains? out0 (coll-ids 1)) "coll0 tail link in out-ids")
        (is (contains? out2 (head-ids 2)))
        (is (contains? out2 (coll-ids 3))
            "cdr link records tail collection in out-ids")
        (is (= 30 (cell/cell-strongest (net/network-env-lookup n' (head-ids 2)))))
        (is (strongest/compound-subnet-continuation?
             (cell/cell-strongest (net/network-env-lookup n' coll2)))
            "coll2 ran effectful strongest for index-2 dispatch")))))

(deftest nested-linked-list-five-layers-single-head-dispatch
  (testing "index-2 dispatch is local: seed only head2, head0 unchanged"
    (let [{:keys [net head-ids coll-ids]} (build-nested-linked-list 5)
          head0 (head-ids 0)
          head2 (head-ids 2)
          coll2 (coll-ids 2)
          n' (-> net
                 (net/assoc-net-cell head2 (cell/cell 42 42))
                 (run-from [head2]))]
      (is (= 42 (cell/cell-strongest (net/network-env-lookup n' head2))))
      (is (value/nothing? (cell/cell-strongest (net/network-env-lookup n' head0))))
      (is (strongest/compound-subnet-continuation?
           (cell/cell-strongest (net/network-env-lookup n' coll2)))))))

(deftest compound-sync-installs-missing-slot
  (testing "compound sync installs missing slot entry into target subnet"
    (let [outer (new-node-id)
          n (-> net/empty-net (net/assoc-net-cell outer (cell/cell 7 7)))
          source-state (subnet/merge-compound-data
                        (state/empty-compound-subnet)
                        (update/compound-update {:head outer})
                        n)
          sync (update/compound-sync (state/state-subnet source-state) [outer])
          merged (subnet/merge-compound-sync (state/empty-compound-subnet) sync n)]
      (is (state/compound-subnet-state? merged))
      (is (contains? (state/state-out-ids merged) outer))
      (is (= 7 (avatar/avatar-strongest (state/state-subnet merged) outer))))))

(deftest compound-sync-propagator-conflict-contradiction
  (testing "conflicting propagators on same slot return contradiction"
    (let [outer (new-node-id)
          inner (new-node-id)
          mk-subnet (fn [f]
                      (-> net/empty-net
                          (net/net-with-graph {inner (graph/node #{} #{})})
                          (net/net-with-env {inner (prop/prop f)})
                          (net/net-with-dict {outer inner})))
          target (state/compound-state (mk-subnet (fn [_ _ _] [])) #{outer})
          source (mk-subnet (fn [_ _ _] [:different]))
          sync (update/compound-sync source [outer])]
      (is (value/contradiction?
           (subnet/merge-compound-sync target sync net/empty-net))))))
