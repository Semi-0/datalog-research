(ns propagators.compound-object-network-slot-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.compound-object.network-slot :as network-slot]
            [propagators.datastructures.named-network :as named]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.message :as msg]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.scoped-address :as scoped]))

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

(deftest network-slot-install-does-not-touch-collection-cell
  (let [parent (new-node-id)
        coll (new-node-id)
        n0 (nb/install-cells [parent coll])
        [_slot-prop n1] ((obj/p:network-slot :x parent coll) n0)]
    (is (value/nothing? (net/network-cell-value n1 coll)))))

(deftest network-slot-activation-emits-accessor-declaration
  (let [parent (new-node-id)
        coll (new-node-id)
        n0 (nb/install-cells [parent coll])
        [m] ((network-slot/network-slot-activation :x parent coll) nil nil n0)]
    (is (= coll (msg/message-id m)))
    (is (= #{parent}
           (obj/accessor-parent-ids (msg/message-value m) :x)))))

(deftest accessor-declaration-is-refined-in-cell-content
  (let [parent (new-node-id)
        declaration (obj/accessor-declaration :x parent)
        merged (merge/cell-merge value/nothing declaration net/empty-net)]
    (is (obj/accessor-network? merged))
    (is (= #{parent}
           (obj/accessor-parent-ids merged :x)))
    (is (some? (net/network-dict-entry
                merged
                (obj/internal-metadata-key :accessor :x :canonical-cell))))
    (is (some? (net/network-dict-entry
                merged
                (obj/internal-metadata-key :accessor :x :avatar parent))))))

(deftest accessor-declaration-merge-is-idempotent
  (let [parent (new-node-id)
        declaration (obj/accessor-declaration :x parent)
        once (merge/cell-merge value/nothing declaration net/empty-net)
        twice (merge/cell-merge once declaration net/empty-net)]
    (is (= (cell-count once) (cell-count twice)))
    (is (= (prop-count once) (prop-count twice)))
    (is (= true (named/named-network->= once twice)))
    (is (= true (named/named-network->= twice once)))))

(deftest accessor-declaration-merge-is-order-independent
  (let [p1 (new-node-id)
        p2 (new-node-id)
        d1 (obj/accessor-declaration :x p1)
        d2 (obj/accessor-declaration :x p2)
        left (-> value/nothing
                 (merge/cell-merge d1 net/empty-net)
                 (merge/cell-merge d2 net/empty-net))
        right (-> value/nothing
                  (merge/cell-merge d2 net/empty-net)
                  (merge/cell-merge d1 net/empty-net))]
    (is (= #{p1 p2} (obj/accessor-parent-ids left :x)))
    (is (= #{p1 p2} (obj/accessor-parent-ids right :x)))
    (is (= 1 (count (filter #(= (obj/internal-metadata-key :accessor :x :canonical-cell) %)
                            (keys (net/net-dict-or-empty left))))))
    (is (= true (named/named-network->= left right)))
    (is (= true (named/named-network->= right left)))))

(deftest accessor-declaration-installs-canonical-cell-and-parent-routes
  (let [p1 (new-node-id)
        p2 (new-node-id)
        merged (-> value/nothing
                   (merge/cell-merge (obj/accessor-declaration :x p1) net/empty-net)
                   (merge/cell-merge (obj/accessor-declaration :x p2) net/empty-net))
        dict (net/net-dict-or-empty merged)
        sync-prefix (obj/internal-metadata-key :accessor-sync)
        sync-key? #(and (vector? %)
                        (= sync-prefix
                           (vec (take (count sync-prefix) %))))]
    (is (some? (get dict (obj/internal-metadata-key :accessor :x :canonical-cell))))
    (is (some? (get dict (obj/internal-metadata-key :accessor :x :avatar p1))))
    (is (some? (get dict (obj/internal-metadata-key :accessor :x :avatar p2))))
    (is (= 4 (count (filter sync-key? (keys dict)))))))

(deftest slot-access-reuses-one-existing-outer-cell
  (let [parent (new-node-id)
        fallback (new-node-id)
        coll (new-node-id)
        n0 (nb/install-cells [parent fallback coll])
        [slot-prop n1] ((obj/p:slot :x parent coll) n0)
        n2 (nb/run-propagators n1 [slot-prop])
        prop-count-before (prop-count n2)
        [cell-id prop-ids n3] (obj/install-slot-access n2 :x coll fallback)]
    (is (= parent cell-id))
    (is (empty? prop-ids))
    (is (= prop-count-before (prop-count n3)))
    (is (= n2 n3))))

(deftest slot-access-declares-one-live-fallback-for-a-missing-slot
  (let [fallback (new-node-id)
        coll (new-node-id)
        n0 (nb/install-cells [fallback coll])
        [cell-id prop-ids n1] (obj/install-slot-access n0 :x coll fallback)]
    (is (= fallback cell-id))
    (is (= 1 (count prop-ids)))
    (is (= (inc (prop-count n0)) (prop-count n1)))))

(deftest slot-access-does-not-pick-an-arbitrary-parent
  (let [p1 (new-node-id)
        p2 (new-node-id)
        fallback (new-node-id)
        coll (new-node-id)
        n0 (nb/install-cells [p1 p2 fallback coll])
        [p1-prop n1] ((obj/p:slot :x p1 coll) n0)
        n2 (nb/run-propagators n1 [p1-prop])
        [p2-prop n3] ((obj/p:slot :x p2 coll) n2)
        n4 (nb/run-propagators n3 [p2-prop])
        [preferred-id preferred-props preferred-net]
        (obj/install-slot-access n4 :x coll p2)
        [fallback-id fallback-props fallback-net]
        (obj/install-slot-access n4 :x coll fallback)]
    (is (= p2 preferred-id))
    (is (empty? preferred-props))
    (is (= n4 preferred-net))
    (is (= fallback fallback-id))
    (is (= 1 (count fallback-props)))
    (is (= (inc (prop-count n4)) (prop-count fallback-net)))))

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

(deftest network-slot-preserves-plain-named-network-values-and-internals
  (testing "plain named networks are preserved instead of converted to source slots"
    (let [slot-id (new-node-id)
          internal-id (new-node-id)
          internal-key (obj/internal-metadata-key :plain-network :internal-cell)
          source (-> net/empty-net
                     (nb/install-cell slot-id 42 42)
                     (net/assoc-net-dict-entry :x slot-id)
                     (nb/install-cell internal-id :kept :kept)
                     (net/assoc-net-dict-entry internal-key internal-id)
                     (net/assoc-net-dict-entry :plain-tag {:kept? true}))
          {:keys [parent-value collection-value]} (run-network-slot :x
                                                                    source
                                                                    ::none)]
      (is (= 42 parent-value))
      (is (obj/accessor-network? collection-value))
      (is (= 42 (obj/slot-value collection-value :x)))
      (is (empty? (obj/accessor-source-slots collection-value)))
      (is (contains? (obj/accessor-slot-keys collection-value) :x))
      (is (not (contains? (obj/accessor-slot-keys collection-value)
                          :plain-tag)))
      (is (= internal-id (net/network-dict-entry collection-value internal-key)))
      (is (= :kept (net/network-cell-value collection-value internal-id)))
      (is (= {:kept? true}
             (net/network-dict-entry collection-value :plain-tag))))))

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

(deftest network-slot-fans-out-to-scoped-address-participants
  (testing "scoped participants receive value updates without becoming local cells"
    (let [parent (new-node-id)
          scoped-target (new-node-id)
          coll (new-node-id)
          child-local (new-node-id)
          child-ref (scoped/cell-ref [:test/scope] child-local)
          n0 (nb/install-cells [parent scoped-target coll])
          [slot-prop n1] ((obj/p:network-slot :x parent coll) n0)
          n2 (-> n1
                 (net/assoc-net-dict-entry child-ref
                                           [:dispatch/local scoped-target])
                 (nb/run-propagators [slot-prop]))
          [tasks n3] (core/eval-cell coll
                                      (msg/message
                                       coll
                                       (obj/accessor-declaration :x child-ref))
                                      n2)
          n4 (core/run-tasks tasks n3)
          coll-with-scoped (net/network-cell-value n4 coll)
          n5 (-> n4
                 (nb/seed-cell parent 10)
                 (nb/run-propagators [slot-prop]))]
      (is (= 10 (net/network-cell-value n5 parent)))
      (is (= 10 (net/network-cell-value n5 scoped-target)))
      (is (= coll-with-scoped (net/network-cell-value n5 coll)))
      (is (not (contains? (net/net-env n5) child-ref))))))

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
      (is (empty? (obj/slot-declarations-for n4 coll)))
      (is (= {:strategy :network-slot}
             (get-in (obj/accessor-declarations-for n4 coll) [:x parent]))))))

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

(deftest late-accessors-read-settled-p-cons-chain
  (testing "late root-to-tail p:car/p:cdr declarations are order-insensitive"
    (let [values [1 2 3 4 5]
          heads (vec (repeatedly (count values) new-node-id))
          colls (vec (repeatedly (count values) new-node-id))
          terminal (new-node-id)
          tails (vec (repeatedly (count values) new-node-id))
          outs (vec (repeatedly (count values) new-node-id))
          n0 (nb/install-cells (into [] cat [heads colls [terminal] tails outs]))
          build (reduce
                 (fn [{:keys [net props]} i]
                   (let [tail (if (= i (dec (count values)))
                                terminal
                                (colls (inc i)))
                         [[car-prop cdr-prop] net'] ((obj/p:cons (heads i)
                                                                  tail
                                                                  (colls i))
                                                     net)]
                     {:net net'
                      :props (conj props car-prop cdr-prop)}))
                 {:net n0 :props []}
                 (range (count values)))
          settled (-> (:net build)
                      (nb/seed-cell terminal value/nothing)
                      (#(reduce (fn [n [head v]]
                                  (nb/seed-cell n head v))
                                %
                                (map vector heads values)))
                      (nb/run-propagators (:props build)))
          path (reduce
                (fn [{:keys [net props prev]} i]
                  (let [[car-prop net1] ((obj/p:car (outs i) prev) net)
                        [cdr-prop net2] ((obj/p:cdr (tails i) prev) net1)]
                    {:net net2
                     :props (conj props car-prop cdr-prop)
                     :prev (tails i)}))
                {:net settled :props [] :prev (colls 0)}
                (range (count values)))
          read-net (nb/run-propagators (:net path) (:props path))]
      (is (= values
             (mapv #(net/network-cell-value read-net %) outs)))
      (is (value/nothing? (net/network-cell-value read-net (last tails)))))))

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
