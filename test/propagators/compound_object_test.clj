(ns propagators.compound-object-test
  "Experimental bidirectional compound object slots."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.deprecated.compound-data :as linked]
            [propagators.datastructures.compound_subnet_state :as state]
            [propagators.datastructures.named-network :as named]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.layered :as layered]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.arithmetic :as arithmetic]
            [propagators.stdlib.arithmetic.base :as base]
            [propagators.stdlib.arithmetic.provenance :as provenance]
            [propagators.stdlib.layered :as layered-ops]
            [propagators.stdlib.prop :as stdlib-prop]))

(defrecord ExampleRecord [left right])

(defn- sync-prop-keys [collection-net]
  (net/network-dict-keys-tagged collection-net obj/slot-sync-key))

(defn- reduce-prop-keys [collection-net]
  (net/network-dict-keys-tagged collection-net obj/reduce-sync-key))

(defn- tap-prop-keys [collection-net]
  (->> (keys (net/net-dict-or-empty collection-net))
       (filter #(and (vector? %) (contains? #{:slot-tap :effect-tap} (first %))))
       set))

(defn- effect-tap-prop-entries [collection-net]
  (let [g (net/net-graph collection-net)]
    (->> (net/net-env collection-net)
         (filter (fn [[id entry]]
                   (and (prop/prop? entry)
                        (empty? (graph/node-output-ids (get g id))))))
         vec)))

(defn- build-slot-net [slot-installer]
  (let [parent (new-node-id)
        coll (new-node-id)
        n (nb/install-cells [parent coll])
        [prop-id n] ((slot-installer parent coll) n)]
    {:net n :parent parent :coll coll :prop-id prop-id}))

(defn- slot-set-merge-net
  []
  (let [acc (new-node-id)
        update (new-node-id)
        out (new-node-id)
        n0 (nb/install-cells [acc update out])
        merge-update (fn [acc* update*]
                       (conj (or acc* #{})
                             [(:slot update*) (:value update*)]))
        [_ n1] (((prop/primitive-propagator merge-update) acc update out) n0)]
    (-> n1
        (net/assoc-net-dict-entry :acc acc)
        (net/assoc-net-dict-entry :update update)
        (net/assoc-net-dict-entry :out out))))

(defn- run-slot-set-reducer
  [source]
  (let [coll (new-node-id)
        merge-net (new-node-id)
        init (new-node-id)
        out (new-node-id)
        n0 (nb/install-cells [coll merge-net init out])
        [reduce-prop n1] ((obj/p:reduce coll merge-net init out) n0)
        n2 (-> n1
               (nb/seed-cell coll source)
               (nb/seed-cell merge-net (slot-set-merge-net))
               (nb/seed-cell init #{})
               (nb/run-propagators [reduce-prop]))]
    (net/network-cell-value n2 out)))

(defn- compound-reducible?
  [v]
  (not (value/contradiction? (obj/compound-object v))))

(defn- nested-slot-set-via-shallow-reducer
  ([source]
   (nested-slot-set-via-shallow-reducer [] source))
  ([path source]
   (reduce (fn [acc [slot-key slot-value]]
             (if (= :count slot-key)
               acc
               (let [path' (conj path slot-key)]
                 (if (compound-reducible? slot-value)
                   (into acc
                         (nested-slot-set-via-shallow-reducer
                          path'
                          slot-value))
                   (conj acc [path' slot-value])))))
           #{}
           (run-slot-set-reducer source))))

(defn- build-nested-with-cons [layers]
  (let [sentinel (new-node-id)
        ids (vec (repeatedly (+ (* 2 layers) 1) new-node-id))
        coll-ids (mapv #(nth ids (+ (* 2 %) 1)) (range layers))
        head-ids (mapv #(nth ids (* 2 %)) (range layers))
        n (nb/install-cells (conj ids sentinel))]
    (reduce
     (fn [{:keys [net props] :as acc} i]
       (let [h (head-ids i)
             c (coll-ids i)
             t (if (< i (dec layers)) (coll-ids (inc i)) sentinel)
             [[car-prop cdr-prop] n'] ((obj/p:cons h t c) net)]
         (assoc acc :net n' :props (conj props car-prop cdr-prop))))
     {:net n :head-ids head-ids :coll-ids coll-ids :sentinel sentinel :props []}
     (range layers))))

(defn- build-three-layer-cons-with-accessor []
  (let [{:keys [net head-ids coll-ids props]} (build-nested-with-cons 3)
        [coll0 coll1 coll2] coll-ids
        [head0 head1 head2] head-ids
        out (new-node-id)
        n (nb/install-cell net out)
        [n tasks] (reduce
                   (fn [[n tasks] installer]
                     (nb/install-propagator! n tasks installer))
                   [n tq/empty-queue]
                   [(obj/p:cdr coll1 coll0)
                    (obj/p:cdr coll2 coll1)
                    (obj/p:car out coll2)])]
    {:net n
     :tasks tasks
     :props props
     :coll0 coll0
     :coll1 coll1
     :coll2 coll2
     :head0 head0
     :head1 head1
     :head2 head2
     :out out}))

(deftest p-car-syncs-parent-value-into-collection-network
  (testing "parent value enters the collection named-network :car slot"
    (let [{:keys [net parent coll prop-id]} (build-slot-net obj/p:car)
          n' (-> net
                 (nb/seed-cell parent 10)
                 (nb/run-propagators [prop-id]))
          coll-net (net/network-cell-value n' coll)
          dict (net/net-dict-or-empty coll-net)]
      (is (= 10 (obj/slot-strongest coll-net :car)))
      (is (contains? dict parent))
      (is (= value/nothing (net/network-cell-value coll-net (get dict parent)))
          "durable collection value keeps accessor avatars empty")
      (is (contains? (get-in dict [:slot-index :car]) parent)))))

(deftest p-car-syncs-collection-slot-out-to-parent
  (testing "collection slot value dispatches through tapped avatar to parent"
    (let [{:keys [net parent coll prop-id]} (build-slot-net obj/p:car)
          exec-net (obj/attach-slot-sync (obj/empty-cons-net) :car parent net)
          slot (net/network-dict-entry exec-net :car)
          coll-value (net/assoc-net-cell exec-net slot (cell/cell 42 42))
          n' (-> net
                 (nb/seed-cell coll coll-value)
                 (nb/run-propagators [prop-id]))]
      (is (= 42 (net/network-cell-value n' parent))))))

(deftest p-car-fans-out-slot-update-to-all-indexed-parents
  (testing "one slot run emits messages only for tapped parents updated in the subnet"
    (let [p1 (new-node-id)
          p2 (new-node-id)
          coll (new-node-id)
          n0 (nb/install-cells [p1 p2 coll])
          [prop-id n0] ((obj/p:car p1 coll) n0)
          n0 (-> n0 (nb/seed-cell p1 value/nothing) (nb/seed-cell p2 value/nothing))
          exec-net (obj/attach-slot-sync (obj/empty-cons-net) :car p1 n0)
          exec-net (obj/attach-slot-sync exec-net :car p2 n0)
          slot (net/network-dict-entry exec-net :car)
          coll-value (net/assoc-net-cell exec-net slot (cell/cell 77 77))
          n' (-> n0
                 (nb/seed-cell coll coll-value)
                 (nb/run-propagators [prop-id]))]
      (is (= 77 (net/network-cell-value n' p1)))
      (is (= 77 (net/network-cell-value n' p2))))))

(deftest p-car-reuses-existing-avatar
  (testing "repeated equivalent parent updates reuse the same avatar and sync props"
    (let [{:keys [net parent coll prop-id]} (build-slot-net obj/p:car)
          n1 (-> net (nb/seed-cell parent 1) (nb/run-propagators [prop-id]))
          coll-net1 (net/network-cell-value n1 coll)
          avatar1 (get (net/net-dict-or-empty coll-net1) parent)
          sync-keys1 (sync-prop-keys coll-net1)
          n2 (-> n1 (nb/seed-cell parent 1) (nb/run-propagators [prop-id]))
          coll-net2 (net/network-cell-value n2 coll)
          avatar2 (get (net/net-dict-or-empty coll-net2) parent)
          sync-keys2 (sync-prop-keys coll-net2)]
      (is (= avatar1 avatar2))
      (is (= sync-keys1 sync-keys2))
      (is (= 1 (obj/slot-strongest coll-net2 :car))))))

(deftest p-car-creates-missing-slot-before-attach
  (testing "slot sync can establish a missing slot cell"
    (let [{:keys [net parent coll prop-id]} (build-slot-net obj/p:car)
          sparse (net/net-with-dict net/empty-net {:slot-index {}})
          n' (-> net
                 (nb/seed-cell parent 5)
                 (nb/seed-cell coll sparse)
                 (nb/run-propagators [prop-id]))
          coll-net (net/network-cell-value n' coll)]
      (is (net/network-dict-entry coll-net :car))
      (is (= 5 (obj/slot-strongest coll-net :car))))))

(deftest p-car-accepts-subsuming-named-network-update
  (testing "subsuming named-network values replace weaker slot evidence"
    (let [{:keys [net parent coll prop-id]} (build-slot-net obj/p:car)
          weak (nb/named-cell-net [[:x true]])
          strong (nb/add-named-cell weak :y false)
          n1 (-> net (nb/seed-cell parent weak) (nb/run-propagators [prop-id]))
          n2 (-> n1 (nb/seed-cell parent strong) (nb/run-propagators [prop-id]))
          coll-net (net/network-cell-value n2 coll)]
      (is (= #{strong} (obj/slot-content coll-net :car)))
      (is (= strong (obj/slot-strongest coll-net :car))))))

(deftest named-network-cell-updated-suppresses-equivalent-collection-update
  (testing "strongest-equivalent named network content does not wake outputs"
    (let [n (obj/empty-cons-net)
          stronger (-> n
                       (net/net-with-dict
                        (assoc (net/net-dict-or-empty n)
                               :slot-index {:car #{(new-node-id)} :cdr #{}})))]
      (is (false? (merge/cell-updated? #{n} n net/empty-net)))
      (is (true? (merge/cell-updated? stronger n net/empty-net))))))

(deftest collection-noop-update-does-not-reenqueue-slot-sync
  (testing "equivalent named-network update changes raw content but not strongest"
    (let [parent (new-node-id)
          coll (new-node-id)
          n0 (nb/install-cells [parent coll])
          [prop-id n0] ((obj/p:car parent coll) n0)
          coll-net (obj/empty-cons-net)
          n0 (net/assoc-net-cell n0 coll (cell/cell coll-net coll-net))
          [tasks _n'] (core/eval-cell coll (message coll coll-net) n0)]
      (is (prop/prop? (net/network-lookup-propagator n0 prop-id)))
      (is (tq/queue-empty? tasks)))))

(deftest repeated-equivalent-slot-activation-skips-subnet
  (testing "once slot and parent agree, rerunning the slot prop emits no messages"
    (let [{:keys [net parent prop-id]} (build-slot-net obj/p:car)
          n1 (-> net
                 (nb/seed-cell parent 10)
                 (nb/run-propagators [prop-id]))
          f (prop/prop-f (net/network-lookup-propagator n1 prop-id))
          messages (f nil nil n1)]
      (is (empty? messages)))))

(deftest unchanged-empty-slot-registration-emits-only-topology
  (testing "a new nothing-valued accessor registers topology without subnet execution"
    (let [{:keys [net prop-id]} (build-slot-net obj/p:car)
          f (prop/prop-f (net/network-lookup-propagator net prop-id))
          messages (f nil nil net)]
      (is (= 1 (count messages))))))

(deftest p-slot-records-topology-declaration-without-materializing-data
  (testing "installing p:slot records declaration metadata but does not update the collection"
    (let [parent (new-node-id)
          coll (new-node-id)
          n0 (nb/install-cells [parent coll])
          [prop-id n1] ((obj/p:slot :car parent coll) n0)
          declarations (obj/slot-declarations-for n1 coll)]
      (is (= #{:car} (set (keys declarations))))
      (is (= #{parent} (set (keys (get declarations :car)))))
      (is (= {:prop-id prop-id} (get-in declarations [:car parent])))
      (is (nil? (obj/slot-value (net/network-cell-value n1 coll) :car)))
      (is (= #{} (obj/public-slot-keys (net/network-cell-value n1 coll))))))

  (testing "duplicate declarations are idempotent at the topology level"
    (let [parent (new-node-id)
          coll (new-node-id)
          n0 (nb/install-cells [parent coll])
          [_prop-id n1] ((obj/p:slot :car parent coll) n0)
          [prop-id* n2] ((obj/p:slot :car parent coll) n1)
          declarations (obj/slot-declarations-for n2 coll)]
      (is (= #{:car} (set (keys declarations))))
      (is (= #{parent} (set (keys (get declarations :car)))))
      (is (= {:prop-id prop-id*} (get-in declarations [:car parent]))))))

(deftest p-cons-syncs-car-and-cdr-independently
  (testing "p:cons installs independent bidirectional slot constraints"
    (let [head (new-node-id)
          tail (new-node-id)
          coll (new-node-id)
          n (nb/install-cells [head tail coll])
          [[car-prop cdr-prop] n] ((obj/p:cons head tail coll) n)
          n' (-> n
                 (nb/seed-cell head 10)
                 (nb/seed-cell tail 20)
                 (nb/run-propagators [car-prop cdr-prop]))
          coll-net (net/network-cell-value n' coll)]
      (is (= 10 (obj/slot-strongest coll-net :car)))
      (is (= 20 (obj/slot-strongest coll-net :cdr))))))

(deftest compound-object-normalizes-map-vector-and-record-slots
  (testing "maps and records expose public slot cells"
    (let [m (obj/compound-object {:left 1 :right 2})
          r (obj/compound-object (->ExampleRecord 3 4))]
      (is (= #{:left :right} (obj/public-slot-keys m)))
      (is (= 1 (obj/slot-value m :left)))
      (is (= 4 (obj/slot-value r :right)))))

  (testing "vectors expose indexes and derived count"
    (let [v (obj/compound-object [:a :b])]
      (is (= #{0 1 :count} (obj/public-slot-keys v)))
      (is (= :a (obj/slot-value v 0)))
      (is (= 2 (obj/slot-value v :count)))))

  (testing "slot conflicts are local to the slot cell"
    (let [left (obj/compound-object {:a 1 :b 2})
          right (obj/compound-object {:a 9 :c 3})
          joined (named/join left right)]
      (is (= value/contradiction (obj/slot-value joined :a)))
      (is (= 2 (obj/slot-value joined :b)))
      (is (= 3 (obj/slot-value joined :c))))))

(deftest p-slot-syncs-plain-map-record-and-vector-values
  (testing "p:slot reads a map slot and localizes conflicting writes"
    (let [parent (new-node-id)
          coll (new-node-id)
          n0 (nb/install-cells [parent coll])
          [prop-id n1] ((obj/p:slot :left parent coll) n0)
          n2 (-> n1
                 (nb/seed-cell coll {:left 1 :right 2})
                 (nb/run-propagators [prop-id]))
          n3 (-> n2
                 (nb/seed-cell parent 10)
                 (nb/run-propagators [prop-id]))
          coll-net (net/network-cell-value n3 coll)]
      (is (= 1 (net/network-cell-value n2 parent)))
      (is (= value/contradiction (obj/slot-value coll-net :left)))
      (is (= 2 (obj/slot-value coll-net :right)))))

  (testing "p:slot writes a missing map slot back into a slot cell"
    (let [parent (new-node-id)
          coll (new-node-id)
          n0 (nb/install-cells [parent coll])
          [prop-id n1] ((obj/p:slot :left parent coll) n0)
          n2 (-> n1
                 (nb/seed-cell coll {:right 2})
                 (nb/seed-cell parent 10)
                 (nb/run-propagators [prop-id]))
          coll-net (net/network-cell-value n2 coll)]
      (is (= 10 (obj/slot-value coll-net :left)))
      (is (= 2 (obj/slot-value coll-net :right)))))

  (testing "records expose map-style field slots"
    (let [parent (new-node-id)
          coll (new-node-id)
          n0 (nb/install-cells [parent coll])
          [prop-id n1] ((obj/p:slot :right parent coll) n0)
          n2 (-> n1
                 (nb/seed-cell coll (->ExampleRecord 3 4))
                 (nb/run-propagators [prop-id]))]
      (is (= 4 (net/network-cell-value n2 parent)))))

  (testing "vector indexes are slots"
    (let [parent (new-node-id)
          coll (new-node-id)
          n0 (nb/install-cells [parent coll])
          [prop-id n1] ((obj/p:slot 1 parent coll) n0)
          n2 (-> n1
                 (nb/seed-cell coll [:a :b])
                 (nb/run-propagators [prop-id]))]
      (is (= :b (net/network-cell-value n2 parent)))))

  (testing "vector count is derived and does not resize indexed slots"
    (let [parent (new-node-id)
          coll (new-node-id)
          n0 (nb/install-cells [parent coll])
          [prop-id n1] ((obj/p:slot :count parent coll) n0)
          n2 (-> n1
                 (nb/seed-cell coll [:a :b])
                 (nb/run-propagators [prop-id]))
          n3 (-> n2
                 (nb/seed-cell parent 99)
                 (nb/run-propagators [prop-id]))
          coll-net (net/network-cell-value n3 coll)]
      (is (= 2 (net/network-cell-value n2 parent)))
      (is (= value/contradiction (net/network-cell-value n3 parent)))
      (is (= :a (obj/slot-value coll-net 0)))
      (is (= :b (obj/slot-value coll-net 1)))
      (is (= 2 (obj/slot-value coll-net :count))))))

(deftest p-slot-propagates-nested-slot-updates-to-top-object
  (testing "filling a slot in the second nested object propagates to the top"
    (let [top (new-node-id)
          first (new-node-id)
          first-leaf (new-node-id)
          second (new-node-id)
          leaf (new-node-id)
          n0 (nb/install-cells [top first first-leaf second leaf])
          [n1 tasks] (nb/install-propagator! n0
                                             tq/empty-queue
                                             (obj/p:slot :first first top))
          [n2 tasks] (nb/install-propagator! n1
                                             tasks
                                             (obj/p:slot :value first-leaf first))
          [n3 tasks] (nb/install-propagator! n2
                                             tasks
                                             (obj/p:slot :second second top))
          [n4 tasks] (nb/install-propagator! n3
                                             tasks
                                             (obj/p:slot :value leaf second))
          [n5 tasks] (nb/seed-cell! n4 tasks first-leaf 1)
          n6 (core/run-tasks tasks n5)
          [n7 tasks] (nb/seed-cell! n6 tq/empty-queue leaf 9)
          n8 (core/run-tasks tasks n7)
          top-value (net/network-cell-value n8 top)
          second-value (obj/slot-value top-value :second)]
      (is (= 9 (obj/slot-value second-value :value)))
      (is (= 1 (obj/slot-value (obj/slot-value top-value :first) :value)))))

  (testing "conflicting nested slot updates propagate as a localized contradiction"
    (let [top (new-node-id)
          second (new-node-id)
          leaf (new-node-id)
          n0 (nb/install-cells [top second leaf])
          [n1 tasks] (nb/install-propagator! n0
                                             tq/empty-queue
                                             (obj/p:slot :second second top))
          [n2 tasks] (nb/install-propagator! n1
                                             tasks
                                             (obj/p:slot :value leaf second))
          [n3 tasks] (nb/seed-cell! n2 tasks leaf 2)
          n4 (core/run-tasks tasks n3)
          [n5 tasks] (nb/seed-cell! n4 tq/empty-queue leaf 9)
          n6 (core/run-tasks tasks n5)
          top-value (net/network-cell-value n6 top)
          second-value (obj/slot-value top-value :second)]
      (is (= value/contradiction
             (obj/slot-value second-value :value))))))

(deftest p-reduce-folds-all-compound-slots-from-strongest-reducer-subnet
  (testing "empty source returns init"
    (let [coll (new-node-id)
          merge-net (new-node-id)
          init (new-node-id)
          out (new-node-id)
          n0 (nb/install-cells [coll merge-net init out])
          [reduce-prop n1] ((obj/p:reduce coll merge-net init out) n0)
          n2 (-> n1
                 (nb/seed-cell coll (obj/empty-cons-net))
                 (nb/seed-cell merge-net (slot-set-merge-net))
                 (nb/seed-cell init #{})
                 (nb/run-propagators [reduce-prop]))]
      (is (= #{} (net/network-cell-value n2 out)))))

  (testing "slots arriving car then cdr produce the same final set"
    (let [coll (new-node-id)
          car (new-node-id)
          cdr (new-node-id)
          merge-net (new-node-id)
          init (new-node-id)
          out (new-node-id)
          n0 (nb/install-cells [coll car cdr merge-net init out])
          [car-prop n1] ((obj/p:slot :car car coll) n0)
          [cdr-prop n2] ((obj/p:slot :cdr cdr coll) n1)
          [reduce-prop n3] ((obj/p:reduce coll merge-net init out) n2)
          n4 (-> n3
                 (nb/seed-cell merge-net (slot-set-merge-net))
                 (nb/seed-cell init #{})
                 (nb/seed-cell car 10)
                 (nb/run-propagators [car-prop reduce-prop]))
          n5 (-> n4
                 (nb/seed-cell cdr 20)
                 (nb/run-propagators [cdr-prop]))]
      (is (= #{[:car 10]} (net/network-cell-value n4 out)))
      (is (= #{[:car 10] [:cdr 20]} (net/network-cell-value n5 out)))))

  (testing "slots arriving cdr then car produce the same final set"
    (let [coll (new-node-id)
          car (new-node-id)
          cdr (new-node-id)
          merge-net (new-node-id)
          init (new-node-id)
          out (new-node-id)
          n0 (nb/install-cells [coll car cdr merge-net init out])
          [car-prop n1] ((obj/p:slot :car car coll) n0)
          [cdr-prop n2] ((obj/p:slot :cdr cdr coll) n1)
          [reduce-prop n3] ((obj/p:reduce coll merge-net init out) n2)
          n4 (-> n3
                 (nb/seed-cell merge-net (slot-set-merge-net))
                 (nb/seed-cell init #{})
                 (nb/seed-cell cdr 20)
                 (nb/run-propagators [cdr-prop reduce-prop]))
          n5 (-> n4
                 (nb/seed-cell car 10)
                 (nb/run-propagators [car-prop]))]
      (is (= #{[:cdr 20]} (net/network-cell-value n4 out)))
      (is (= #{[:car 10] [:cdr 20]} (net/network-cell-value n5 out)))))

  (testing "unusable slots and internal keys are ignored"
    (let [coll (new-node-id)
          merge-net (new-node-id)
          init (new-node-id)
          out (new-node-id)
          good-slot (new-node-id)
          unusable-slot (new-node-id)
          source (-> net/empty-net
                     (nb/install-cell good-slot 20 20)
                     (nb/install-cell unusable-slot value/nothing value/nothing)
                     (net/net-with-dict {:good good-slot
                                         :bad unusable-slot
                                         obj/slot-declarations-key {:ignored :metadata}
                                         :slot-index {:good #{}}
                                         [:slot-sync :good] (new-node-id)}))
          n0 (nb/install-cells [coll merge-net init out])
          [reduce-prop n1] ((obj/p:reduce coll merge-net init out) n0)
          n2 (-> n1
                 (nb/seed-cell coll source)
                 (nb/seed-cell merge-net (slot-set-merge-net))
                 (nb/seed-cell init #{})
                 (nb/run-propagators [reduce-prop]))]
      (is (= #{[:good 20]} (net/network-cell-value n2 out))))))

(deftest p-reduce-folds-normalized-map-and-vector-slots
  (testing "plain maps are normalized before reducer-subnet folding"
    (let [coll (new-node-id)
          merge-net (new-node-id)
          init (new-node-id)
          out (new-node-id)
          n0 (nb/install-cells [coll merge-net init out])
          [reduce-prop n1] ((obj/p:reduce coll merge-net init out) n0)
          n2 (-> n1
                 (nb/seed-cell coll {:a 1 :b 2})
                 (nb/seed-cell merge-net (slot-set-merge-net))
                 (nb/seed-cell init #{})
                 (nb/run-propagators [reduce-prop]))]
      (is (= #{[:a 1] [:b 2]} (net/network-cell-value n2 out)))))

  (testing "plain vectors expose indexes and public count to reducer-subnet"
    (let [coll (new-node-id)
          merge-net (new-node-id)
          init (new-node-id)
          out (new-node-id)
          n0 (nb/install-cells [coll merge-net init out])
          [reduce-prop n1] ((obj/p:reduce coll merge-net init out) n0)
          n2 (-> n1
                 (nb/seed-cell coll [:a :b])
                 (nb/seed-cell merge-net (slot-set-merge-net))
                 (nb/seed-cell init #{})
                 (nb/run-propagators [reduce-prop]))]
      (is (= #{[0 :a] [1 :b] [:count 2]} (net/network-cell-value n2 out))))))

(deftest p-reduce-folds-nested-compound-values-shallowly
  (testing "nested maps and vectors are immediate slot values, not recursively folded"
    (let [source {:a 1
                  :nested {:b 2
                           :c [3 4]}}
          folded (run-slot-set-reducer source)]
      (is (= #{[:a 1]
               [:nested {:b 2 :c [3 4]}]}
             folded))
      (is (not (contains? folded [:b 2])))
      (is (not (contains? folded [0 3])))))

  (testing "nested folds require explicit composition of repeated shallow reducers"
    (let [source {:a 1
                  :nested {:b 2
                           :c [3 4]}}]
      (is (= #{[[:a] 1]
               [[:nested :b] 2]
               [[:nested :c 0] 3]
               [[:nested :c 1] 4]}
             (nested-slot-set-via-shallow-reducer source))))))

(deftest p-reduce-installs-accessors-and-folds-independent-of-slot-order
  (testing "slotful source that exists before reducer activation is folded"
    (let [coll (new-node-id)
          car (new-node-id)
          merge-net (new-node-id)
          init (new-node-id)
          out (new-node-id)
          n0 (nb/install-cells [coll car merge-net init out])
          [car-prop n1] ((obj/p:slot :car car coll) n0)
          n2 (-> n1
                 (nb/seed-cell car 10)
                 (nb/run-propagators [car-prop]))
          [reduce-prop n3] ((obj/p:reduce coll merge-net init out) n2)
          n4 (-> n3
                 (nb/seed-cell merge-net (slot-set-merge-net))
                 (nb/seed-cell init #{})
                 (nb/run-propagators [reduce-prop]))
          coll-net (net/network-cell-value n4 coll)]
      (is (= #{[:car 10]} (net/network-cell-value n4 out)))
      (is (seq (reduce-prop-keys coll-net)))
      (is (= #{:car} (obj/public-slot-keys coll-net)))))

  (testing "reducer activated before a later slot still observes the slot update"
    (let [coll (new-node-id)
          car (new-node-id)
          merge-net (new-node-id)
          init (new-node-id)
          out (new-node-id)
          n0 (nb/install-cells [coll car merge-net init out])
          [car-prop n1] ((obj/p:slot :car car coll) n0)
          [reduce-prop n2] ((obj/p:reduce coll merge-net init out) n1)
          n3 (-> n2
                 (nb/seed-cell merge-net (slot-set-merge-net))
                 (nb/seed-cell init #{})
                 (nb/run-propagators [reduce-prop]))
          n4 (-> n3
                 (nb/seed-cell car 10)
                 (nb/run-propagators [car-prop]))
          coll-net (net/network-cell-value n4 coll)]
      (is (= #{} (net/network-cell-value n3 out)))
      (is (= #{[:car 10]} (net/network-cell-value n4 out)))
      (is (seq (reduce-prop-keys coll-net)))
      (is (= #{:car} (obj/public-slot-keys coll-net)))))

  (testing "reducer metadata is internal and does not become folded data"
    (let [coll (new-node-id)
          merge-net (new-node-id)
          init (new-node-id)
          out (new-node-id)
          n0 (nb/install-cells [coll merge-net init out])
          [reduce-prop n1] ((obj/p:reduce coll merge-net init out) n0)
          n2 (-> n1
                 (nb/seed-cell coll {:a 1})
                 (nb/seed-cell merge-net (slot-set-merge-net))
                 (nb/seed-cell init #{})
                 (nb/run-propagators [reduce-prop]))
          coll-net (net/network-cell-value n2 coll)]
      (is (contains? (net/net-dict-or-empty coll-net) :reduce-index))
      (is (seq (reduce-prop-keys coll-net)))
      (is (= #{:a} (obj/public-slot-keys coll-net)))
      (is (= #{[:a 1]} (net/network-cell-value n2 out))))))

(deftest compare-new-slot-sync-with-current-linked-list-local-case
  (testing "new one-layer slot sync exposes the same local car/cdr values as old p:cons"
    (let [head (new-node-id)
          tail (new-node-id)
          coll (new-node-id)
          old-net (nb/install-cells [head tail coll])
          [[car-prop cdr-prop linked-prop] old-net] ((linked/p:cons-scheduled head tail coll) old-net)
          old-net (-> old-net (nb/seed-cell head 10) (nb/seed-cell tail 20))
          old-net (nb/run-propagators old-net [car-prop cdr-prop linked-prop])
          old-content (net/network-cell-content old-net coll)
          old-subnet (state/state-subnet old-content)
          old-dict (net/net-dict-or-empty old-subnet)
          new-head (new-node-id)
          new-tail (new-node-id)
          new-coll (new-node-id)
          new-net (nb/install-cells [new-head new-tail new-coll])
          [[car-prop cdr-prop] new-net] ((obj/p:cons new-head new-tail new-coll) new-net)
          new-net (-> new-net
                      (nb/seed-cell new-head 10)
                      (nb/seed-cell new-tail 20)
                      (nb/run-propagators [car-prop cdr-prop]))
          new-coll-net (net/network-cell-value new-net new-coll)]
      (is (= 10 (net/network-cell-strongest old-subnet (get old-dict head))))
      (is (= 20 (net/network-cell-strongest old-subnet (get old-dict tail))))
      (is (= 10 (obj/slot-strongest new-coll-net :car)))
      (is (= 20 (obj/slot-strongest new-coll-net :cdr))))))

(deftest p-cons-nested-local-layer-syncs-index-two-head
  (testing "p:cons x5: seeding head2 syncs the local coll2 :car slot"
    (let [{:keys [net head-ids coll-ids props]} (build-nested-with-cons 5)
          head2 (nth head-ids 2)
          coll2 (nth coll-ids 2)
          n' (-> net
                 (nb/seed-cell head2 30)
                 (nb/run-propagators props))
          coll2-net (net/network-cell-value n' coll2)]
      (is (= 30 (net/network-cell-value n' head2)))
      (is (= 30 (obj/slot-strongest coll2-net :car))))))

(deftest p-cons-three-layer-accessor-head2-reaches-out
  (testing "new slot-index model supports (car (cdr (cdr coll0))) accessor fan-out"
    (let [{:keys [net tasks head2 out]} (build-three-layer-cons-with-accessor)
          [n tasks] (nb/seed-cell! net tasks head2 30)
          n' (core/run-tasks tasks n)]
      (is (= 30 (net/network-cell-value n' out))))))

(deftest p-cons-three-layer-accessor-three-heads-reaches-out
  (testing "new slot-index model reaches accessor out with all heads seeded"
    (let [{:keys [net tasks head0 head1 head2 out]} (build-three-layer-cons-with-accessor)
          [n tasks] (nb/seed-cell! net tasks head0 10)
          [n tasks] (nb/seed-cell! n tasks head1 20)
          [n tasks] (nb/seed-cell! n tasks head2 30)
          n' (core/run-tasks tasks n)]
      (is (= 10 (net/network-cell-value n' head0)))
      (is (= 30 (net/network-cell-value n' out))))))

(deftest collection-value-does-not-persist-effect-taps
  (testing "collection named-network may store declarative sync structure, but not effect taps"
    (let [{:keys [net parent coll prop-id]} (build-slot-net obj/p:car)
          n' (-> net
                 (nb/seed-cell parent 10)
                 (nb/run-propagators [prop-id]))
          coll-net (net/network-cell-value n' coll)]
      (is (empty? (tap-prop-keys coll-net))
          "effect tap ids are activation-local and should not persist in collection content")
      (is (empty? (effect-tap-prop-entries coll-net))
          "collection content should not retain effect tap propagator entries"))))

(deftest no-linked-list-dispatch-required
  (testing "p:cons installs only car/cdr slot sync propagators"
    (let [head (new-node-id)
          tail (new-node-id)
          coll (new-node-id)
          n (nb/install-cells [head tail coll])
          [[car-prop cdr-prop] n] ((obj/p:cons head tail coll) n)]
      (is (= 2 (count (filter prop/prop? (vals (net/net-env n))))))
      (is (prop/prop? (net/network-lookup-propagator n car-prop)))
      (is (prop/prop? (net/network-lookup-propagator n cdr-prop))))))

(deftest stdlib-primitive-plus-computes-bare-values
  (testing "prop/+ is an ordinary primitive propagator"
    (let [a (new-node-id)
          b (new-node-id)
          out (new-node-id)
          n (nb/install-cells [a b out])
          [plus-prop n] ((stdlib-prop/+ a b out) n)
          n' (-> n
                 (nb/seed-cell a 3)
                 (nb/seed-cell b 4)
                 (nb/run-propagators [plus-prop]))]
      (is (= 7 (net/network-cell-value n' out))))))

(deftest stdlib-primitive-divide-computes-bare-values
  (testing "prop// is an ordinary primitive propagator"
    (let [a (new-node-id)
          b (new-node-id)
          out (new-node-id)
          n (nb/install-cells [a b out])
          [div-prop n] ((stdlib-prop// a b out) n)
          n' (-> n
                 (nb/seed-cell a 60)
                 (nb/seed-cell b 12)
                 (nb/run-propagators [div-prop]))]
      (is (= 5 (net/network-cell-value n' out))))))

(deftest stdlib-layered-plus-retains-provenance-through-compound-object-slots
  (testing "layered/+ computes base and unions provenance slots"
    (let [proc (new-node-id)
          base-closure (new-node-id)
          prov-closure (new-node-id)
          a (new-node-id)
          b (new-node-id)
          out (new-node-id)
          a-base (new-node-id)
          a-prov (new-node-id)
          b-base (new-node-id)
          b-prov (new-node-id)
          n0 (nb/install-cells [proc base-closure prov-closure
                                a b out
                                a-base a-prov b-base b-prov])
          [base-prop n1] ((layered/p:layered-procedure :base base-closure proc) n0)
          [prov-prop n2] ((layered/p:layered-procedure :provenance prov-closure proc) n1)
          [a-base-prop n3] ((obj/p:slot :base a-base a) n2)
          [a-prov-prop n4] ((obj/p:slot :provenance a-prov a) n3)
          [b-base-prop n5] ((obj/p:slot :base b-base b) n4)
          [b-prov-prop n6] ((obj/p:slot :provenance b-prov b) n5)
          p:+ (layered-ops/+ proc)
          [apply-prop n7] ((p:+ a b out) n6)
          n8 (-> n7
                 (nb/seed-cell base-closure base/plus-closure)
                 (nb/seed-cell prov-closure provenance/+)
                 (nb/seed-cell a-base 10)
                 (nb/seed-cell a-prov #{:a})
                 (nb/seed-cell b-base 20)
                 (nb/seed-cell b-prov #{:b})
                 (nb/run-propagators [base-prop
                                      prov-prop
                                      a-base-prop
                                      a-prov-prop
                                      b-base-prop
                                      b-prov-prop
                                      apply-prop]))
          out-object (net/network-cell-value n8 out)]
      (is (= 30 (obj/slot-strongest out-object :base)))
      (is (= #{:a :b} (obj/slot-strongest out-object :provenance))))))

(deftest arithmetic-provenance-combinator-supports-minus-times-and-divide
  (testing "base and provenance arithmetic namespaces provide matching -, *, and / layers"
    (is (map? (arithmetic/base-extension base/minus-closure)))
    (is (map? (arithmetic/base-extension base/times-closure)))
    (is (map? (arithmetic/base-extension base/divide-closure)))
    (is (map? (arithmetic/provenance-extension provenance/-)))
    (is (map? (arithmetic/provenance-extension provenance/*)))
    (is (map? (arithmetic/provenance-extension provenance//)))
    (is (map? (arithmetic/divide-base-extension)))
    (is (map? (arithmetic/divide-provenance-extension)))))
