(ns propagators.compound-object-network-slot-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- prop-count
  [n]
  (count (filter prop/prop? (vals (net/net-env n)))))

(defn- cell-count
  [n]
  (count (remove prop/prop? (vals (net/net-env n)))))

(defn- run-network-slot
  [slot-key coll-value parent-value]
  (let [parent (new-node-id)
        coll (new-node-id)
        n0 (nb/install-cells [parent coll])
        [slot-prop n1] ((obj/p:network-slot slot-key parent coll) n0)
        n2 (cond-> (nb/seed-cell n1 coll coll-value)
             (not= ::none parent-value) (nb/seed-cell parent parent-value))
        n3 (nb/run-propagators n2 [slot-prop])]
    {:net n3
     :parent parent
     :coll coll
     :prop slot-prop
     :parent-value (net/network-cell-value n3 parent)
     :collection-value (net/network-cell-value n3 coll)}))

(deftest network-slot-reads-map-vector-and-record-source-values
  (testing "map slots are projected to outer accessors without durable slot cells"
    (let [{:keys [parent-value collection-value]} (run-network-slot :left
                                                                    {:left 1 :right 2}
                                                                    ::none)]
      (is (= 1 parent-value))
      (is (obj/accessor-network? collection-value))
      (is (nil? (obj/slot-cell-id collection-value :left)))))

  (testing "vector index slots and read-only count are projected"
    (let [idx (run-network-slot 1 [:a :b] ::none)
          cnt (run-network-slot :count [:a :b] ::none)]
      (is (= :b (:parent-value idx)))
      (is (= 2 (:parent-value cnt))))))

(deftest network-slot-keeps-value-updates-off-the-collection-cell
  (testing "after topology exists, an accessor value update syncs peers only"
    (let [p1 (new-node-id)
          p2 (new-node-id)
          coll (new-node-id)
          n0 (nb/install-cells [p1 p2 coll])
          [p1-prop n1] ((obj/p:network-slot :x p1 coll) n0)
          [p2-prop n2] ((obj/p:network-slot :x p2 coll) n1)
          n3 (nb/run-propagators n2 [p1-prop p2-prop])
          coll-before (net/network-cell-value n3 coll)
          n4 (-> n3
                 (nb/seed-cell p2 10)
                 (nb/run-propagators [p2-prop]))
          coll-after (net/network-cell-value n4 coll)]
      (is (= 10 (net/network-cell-value n4 p1)))
      (is (= 10 (net/network-cell-value n4 p2)))
      (is (= coll-before coll-after)))))

(deftest network-slot-repeated-accessor-declaration-is-idempotent
  (testing "duplicate declarations reuse the same collection topology"
    (let [parent (new-node-id)
          coll (new-node-id)
          n0 (nb/install-cells [parent coll])
          [prop-a n1] ((obj/p:network-slot :x parent coll) n0)
          n2 (nb/run-propagators n1 [prop-a])
          coll-a (net/network-cell-value n2 coll)
          [prop-b n3] ((obj/p:network-slot :x parent coll) n2)
          n4 (nb/run-propagators n3 [prop-b])
          coll-b (net/network-cell-value n4 coll)]
      (is (= coll-a coll-b))
      (is (= {:prop-id prop-b :strategy :network-slot}
             (get-in (obj/slot-declarations-for n4 coll) [:x parent]))))))

(deftest network-slot-late-second-accessor-update-reaches-first-accessor
  (testing "the second frame/accessor can update a slot demanded earlier"
    (let [top (new-node-id)
          first (new-node-id)
          second (new-node-id)
          leaf (new-node-id)
          n0 (nb/install-cells [top first second leaf])
          [n1 tasks] (nb/install-propagator! n0 tq/empty-queue
                                             (obj/p:network-slot :first first top))
          [n2 tasks] (nb/install-propagator! n1 tasks
                                             (obj/p:network-slot :second second top))
          [n3 tasks] (nb/install-propagator! n2 tasks
                                             (obj/p:network-slot :value leaf second))
          [n4 tasks] (nb/seed-cell! n3 tasks leaf 9)
          n5 (core/run-tasks tasks n4)]
      (is (= 9 (net/network-cell-value n5 leaf)))
      (is (obj/accessor-network? (net/network-cell-value n5 top)))
      (is (obj/accessor-network? (net/network-cell-value n5 second))))))

(deftest public-slot-defaults-use-network-slot
  (testing "deprecated p:slot facade now delegates to network-slot behavior"
    (let [parent (new-node-id)
          coll (new-node-id)
          n0 (nb/install-cells [parent coll])
          [slot-prop n1] ((obj/p:slot :x parent coll) n0)
          n2 (-> n1
                 (nb/seed-cell parent 10)
                 (nb/run-propagators [slot-prop]))
          coll-value (net/network-cell-value n2 coll)]
      (is (= 10 (net/network-cell-value n2 parent)))
      (is (obj/accessor-network? coll-value))
      (is (nil? (obj/slot-value coll-value :x))))))

(deftest network-cons-chain-routes-nested-car-cdr-accessors
  (testing "new public p:cons/p:car/p:cdr route (car (cdr (cdr coll0)))"
    (let [head0 (new-node-id)
          head1 (new-node-id)
          head2 (new-node-id)
          coll0 (new-node-id)
          coll1 (new-node-id)
          coll2 (new-node-id)
          sentinel (new-node-id)
          c1 (new-node-id)
          c2 (new-node-id)
          out (new-node-id)
          n0 (nb/install-cells [head0 head1 head2 coll0 coll1 coll2
                                sentinel c1 c2 out])
          [[car0 cdr0] n1] ((obj/p:cons head0 coll1 coll0) n0)
          [[car1 cdr1] n2] ((obj/p:cons head1 coll2 coll1) n1)
          [[car2 cdr2] n3] ((obj/p:cons head2 sentinel coll2) n2)
          [path1 n4] ((obj/p:cdr c1 coll0) n3)
          [path2 n5] ((obj/p:cdr c2 c1) n4)
          [path3 n6] ((obj/p:car out c2) n5)
          props [car0 cdr0 car1 cdr1 car2 cdr2 path1 path2 path3]
          n7 (-> n6
                 (nb/seed-cell head2 30)
                 (nb/run-propagators props))]
      (is (= 30 (net/network-cell-value n7 out)))
      (is (obj/accessor-network? (net/network-cell-value n7 coll0)))
      (is (obj/accessor-network? (net/network-cell-value n7 c1)))
      (is (obj/accessor-network? (net/network-cell-value n7 c2))))))

(defn network-slot-benchmark
  "Small comparison helper for REPL/manual runs. Returns shape metrics and
  elapsed nanoseconds for the current slot strategy and network-slot strategy."
  [accessor-count]
  (letfn [(build [installer]
            (let [coll (new-node-id)
                  parents (vec (repeatedly accessor-count new-node-id))
                  n0 (nb/install-cells (conj parents coll))]
              (reduce
               (fn [{:keys [net props] :as acc} parent]
                 (let [[prop-id net'] ((installer :x parent coll) net)]
                   (assoc acc :net net' :props (conj props prop-id))))
               {:net n0 :props [] :parents parents :coll coll}
               parents)))
          (run-case [installer]
            (let [{:keys [net props parents coll] :as ctx} (build installer)
                  setup-start (System/nanoTime)
                  n1 (nb/run-propagators net props)
                  setup-elapsed (- (System/nanoTime) setup-start)
                  coll-before (net/network-cell-value n1 coll)
                  update-start (System/nanoTime)
                  n2 (-> n1
                         (nb/seed-cell (last parents) 10)
                         (nb/run-propagators [(last props)]))
                  update-elapsed (- (System/nanoTime) update-start)
                  coll-after (net/network-cell-value n2 coll)]
              (assoc ctx
                     :net n2
                     :setup-elapsed-ns setup-elapsed
                     :update-elapsed-ns update-elapsed
                     :cell-count (cell-count n2)
                     :prop-count (prop-count n2)
                     :collection-changed-on-update? (not= coll-before coll-after)
                     :first-value (net/network-cell-value n2 (first parents))
                     :last-value (net/network-cell-value n2 (last parents)))))]
    {:accessors accessor-count
     :slot (select-keys (run-case (fn [slot-key parent coll]
                                    (obj/p:legacy-slot slot-key parent coll)))
                        [:setup-elapsed-ns :update-elapsed-ns
                         :cell-count :prop-count :collection-changed-on-update?
                         :first-value :last-value])
     :network-slot (select-keys (run-case obj/p:network-slot)
                                [:setup-elapsed-ns :update-elapsed-ns
                                 :cell-count :prop-count
                                 :collection-changed-on-update?
                                 :first-value :last-value])}))
