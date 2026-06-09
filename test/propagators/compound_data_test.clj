(ns propagators.compound-data-test
  "Linked-list compound_data: p:car, p:cdr, c:linked-list.
  Run: clj -M:test propagators.compound-data-test"
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.avatar :as avatar]
            [propagators.cells.cell :as cell]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.core :refer [run-tasks]]
            [propagators.deprecated.compound-data :as cd]
            [propagators.datastructures.compound_subnet_state :as state]
            [propagators.datastructures.compound_subnet :as subnet]
            [propagators.datastructures.compound_update :as update]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def ^:private compound-data-installers
  {'cd/p:car cd/p:car
   'cd/p:cdr cd/p:cdr
   'cd/c:linked-list cd/c:linked-list})

(defn- run-from [n seed-ids]
  (let [g (net/net-graph n)
        tasks (pop-inputs seed-ids g)]
    (run-tasks tasks n)))

(defn- seed-values [n id->value]
  (reduce
   (fn [n [id v]]
     (net/assoc-net-cell n id (cell/cell v v)))
   n
   id->value))

(defn- seed-and-run [n id->value]
  (let [n' (seed-values n id->value)]
    (run-from n' (mapv first id->value))))

(defn- compile-compound-data [expr]
  (compile/eval-net net/empty-net compound-data-installers expr))

(defn- refs [ctx syms]
  (mapv #(compile/cell-ref ctx %) syms))

(defn- build-flat-linked-list []
  (let [ctx (compile-compound-data
             '(let-cell [head tail coll]
                (cd/p:car head coll)
                (cd/p:cdr tail coll)
                (cd/c:linked-list coll)))]
    {:net (:net ctx)
     :props (:props ctx)
     :head (compile/cell-ref ctx 'head)
     :tail (compile/cell-ref ctx 'tail)
     :coll (compile/cell-ref ctx 'coll)}))

(defn- nested-linked-list-form [head-syms coll-syms]
  (let [cell-syms (vec (interleave head-syms coll-syms))
        layer-forms
        (mapcat
         (fn [i]
           (let [h (head-syms i)
                 c (coll-syms i)
                 next-c (get coll-syms (inc i))]
             (cond-> [(list 'cd/p:car h c)
                      (list 'cd/c:linked-list c)]
               next-c (conj (list 'cd/p:cdr next-c c)))))
         (range (count head-syms)))]
    (apply list 'let-cell cell-syms layer-forms)))

(defn- build-nested-linked-list [layers]
  (let [head-syms (mapv #(symbol (str "head" %)) (range layers))
        coll-syms (mapv #(symbol (str "coll" %)) (range layers))
        ctx (compile-compound-data (nested-linked-list-form head-syms coll-syms))]
    {:net (:net ctx)
     :props (:props ctx)
     :head-ids (refs ctx head-syms)
     :coll-ids (refs ctx coll-syms)
     :tail-ids (subvec (refs ctx coll-syms) 1)
     :layers layers}))

(deftest car-writes-head-update-to-collection
  (testing "p:car merges head id into collection content"
    (let [{:keys [net head coll]} (build-flat-linked-list)
          n' (seed-and-run net [[head 10]])
          content (cell/cell-content (net/network-env-lookup n' coll))]
      (is (state/compound-subnet-state? content))
      (is (contains? (state/state-out-ids content) head)))))

(deftest cdr-writes-tail-update-to-collection
  (testing "p:cdr merges tail id into collection content"
    (let [{:keys [net tail coll]} (build-flat-linked-list)
          n' (seed-and-run net [[tail 20]])
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
          n' (seed-and-run net [[head 10] [tail 20]])
          strongest (cell/cell-strongest (net/network-env-lookup n' coll))]
      (is (state/compound-subnet-state? strongest))
      (is (= 10 (cell/cell-strongest (net/network-env-lookup n' head))))
      (is (= 20 (cell/cell-strongest (net/network-env-lookup n' tail)))))))

(deftest sync-avatar-cell-syncs-existing-avatar
  (testing "re-merge syncs avatar from parent when id already in dict"
    (let [{:keys [net head coll]} (build-flat-linked-list)
          n' (seed-and-run net [[head 10]])
          content (cell/cell-content (net/network-env-lookup n' coll))
          n2 (seed-values n' [[head 99]])
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
          n' (-> net
                 (seed-and-run [[head1 11]])
                 (seed-and-run [[head0 10]]))]
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
          n' (-> net
                 (seed-and-run [[h2 3]])
                 (seed-and-run [[h0 1]]))]
      (is (state/compound-subnet-state? (cell/cell-content (net/network-env-lookup n' c2))))
      (is (= 3 (cell/cell-strongest (net/network-env-lookup n' h2))))
      (is (= 1 (cell/cell-strongest (net/network-env-lookup n' h0)))))))

(deftest nested-linked-list-five-layers-wiring-pattern
  (testing "5 cons cells: (p:cdr coll_{i+1} coll_i), (p:car h_i coll_i), (c:linked-list coll_i)"
    (let [{:keys [net head-ids coll-ids]} (build-nested-linked-list 5)
          values [10 20 30 40 50]
          n (seed-values net (map vector head-ids values))
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
        (is (state/compound-subnet-state?
             (cell/cell-strongest (net/network-env-lookup n' coll2)))
            "coll2 strongest is structural state; run happens in c:linked-list")))))

(deftest nested-linked-list-five-layers-single-head-dispatch
  (testing "index-2 dispatch is local: seed only head2, head0 unchanged"
    (let [{:keys [net head-ids coll-ids]} (build-nested-linked-list 5)
          head0 (head-ids 0)
          head2 (head-ids 2)
          coll2 (coll-ids 2)
          n' (seed-and-run net [[head2 42]])]
      (is (= 42 (cell/cell-strongest (net/network-env-lookup n' head2))))
      (is (value/nothing? (cell/cell-strongest (net/network-env-lookup n' head0))))
      (is (state/compound-subnet-state?
           (cell/cell-strongest (net/network-env-lookup n' coll2)))))))

(deftest compound-sync-installs-missing-slot
  (testing "compound sync installs missing slot entry into target subnet"
    (let [outer (new-node-id)
          n (seed-values net/empty-net [[outer 7]])
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
