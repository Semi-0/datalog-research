(ns propagators-compound-object-test
  "Experimental bidirectional compound object slots."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.compound_data :as linked]
            [propagators.datastructures.compound_subnet_state :as state]
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

(defn- sync-prop-keys [collection-net]
  (net/network-dict-keys-tagged collection-net obj/slot-sync-key))

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
          base-extension (new-node-id)
          prov-extension (new-node-id)
          a (new-node-id)
          b (new-node-id)
          out (new-node-id)
          a-base (new-node-id)
          a-prov (new-node-id)
          b-base (new-node-id)
          b-prov (new-node-id)
          n0 (nb/install-cells [proc base-extension prov-extension
                                a b out
                                a-base a-prov b-base b-prov])
          [base-prop n1] ((layered/p:layered-procedure proc base-extension) n0)
          [prov-prop n2] ((layered/p:layered-procedure proc prov-extension) n1)
          [a-base-prop n3] ((obj/p:slot :base a-base a) n2)
          [a-prov-prop n4] ((obj/p:slot :provenance a-prov a) n3)
          [b-base-prop n5] ((obj/p:slot :base b-base b) n4)
          [b-prov-prop n6] ((obj/p:slot :provenance b-prov b) n5)
          p:+ (layered-ops/+ proc)
          [apply-prop n7] ((p:+ a b out) n6)
          n8 (-> n7
                 (nb/seed-cell base-extension (arithmetic/base-extension base/plus-closure))
                 (nb/seed-cell prov-extension (arithmetic/provenance-extension provenance/+))
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
