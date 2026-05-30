(ns propagators-compound-object-test
  "Experimental bidirectional compound object slots."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell :refer [construct-cell]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.compound_data :as linked]
            [propagators.datastructures.compound_subnet_state :as state]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn- install-cell [n id]
  (second ((construct-cell id) n)))

(defn- install-cells [ids]
  (reduce install-cell net/empty-net ids))

(defn- seed-cell [n id v]
  (net/assoc-net-cell n id (cell/cell v v)))

(defn- run-props [n prop-ids]
  (core/run-tasks (tq/enqueue-all tq/empty-queue prop-ids) n))

(defn- strongest [n id]
  (cell/cell-strongest (net/network-env-lookup n id)))

(defn- content [n id]
  (cell/cell-content (net/network-env-lookup n id)))

(defn- collection-net [n coll]
  (strongest n coll))

(defn- slot-strongest [collection-net slot-key]
  (net/network-cell-strongest collection-net (obj/slot-id collection-net slot-key)))

(defn- slot-content [collection-net slot-key]
  (net/network-cell-content collection-net (obj/slot-id collection-net slot-key)))

(defn- sync-prop-keys [collection-net]
  (->> (keys (net/net-dict-or-empty collection-net))
       (filter #(and (vector? %) (= :slot-sync (first %))))
       set))

(defn- tap-prop-keys [collection-net]
  (->> (keys (net/net-dict-or-empty collection-net))
       (filter #(and (vector? %) (= :slot-tap (first %))))
       set))

(defn- effect-tap-prop-entries [collection-net]
  (let [g (net/net-graph collection-net)]
    (->> (net/net-env collection-net)
         (filter (fn [[id entry]]
                   (and (prop/prop? entry)
                        (empty? (graph/node-output-ids (get g id))))))
         vec)))

(defn- named-cell-net [named-values]
  (reduce
   (fn [n [k v]]
     (let [id (new-node-id)
           [_ n'] ((construct-cell id v v) n)]
       (net/net-with-dict n' (assoc (net/net-dict-or-empty n') k id))))
   net/empty-net
   named-values))

(defn- add-named-cell [n k v]
  (let [id (new-node-id)
        [_ n'] ((construct-cell id v v) n)]
    (net/net-with-dict n' (assoc (net/net-dict-or-empty n') k id))))

(defn- build-slot-net [slot-installer]
  (let [parent (new-node-id)
        coll (new-node-id)
        n (install-cells [parent coll])
        [prop-id n] ((slot-installer parent coll) n)]
    {:net n :parent parent :coll coll :prop-id prop-id}))

(defn- install-prop! [n tasks installer]
  (let [[prop-id n'] (installer n)]
    [n' (tq/enqueue tasks prop-id)]))

(defn- seed-cell! [n tasks id v]
  (let [n' (seed-cell n id v)
        node (get (net/net-graph n') id)
        neighbor-props (when node
                         (filter #(prop/prop? (get (net/net-env n') %))
                                 (into (vec (:inputs node)) (:outputs node))))]
    [n' (tq/enqueue-all tasks neighbor-props)]))

(defn- run-queue [n tasks]
  (core/run-tasks tasks n))

(defn- build-nested-with-cons* [layers]
  (let [sentinel (new-node-id)
        ids (vec (repeatedly (+ (* 2 layers) 1) new-node-id))
        coll-ids (mapv #(nth ids (+ (* 2 %) 1)) (range layers))
        head-ids (mapv #(nth ids (* 2 %)) (range layers))
        n (install-cells (conj ids sentinel))]
    (reduce
     (fn [{:keys [net props] :as acc} i]
       (let [h (head-ids i)
             c (coll-ids i)
             t (if (< i (dec layers)) (coll-ids (inc i)) sentinel)
             [[car-prop cdr-prop] n'] ((obj/p:cons* h t c) net)]
         (assoc acc :net n' :props (conj props car-prop cdr-prop))))
     {:net n :head-ids head-ids :coll-ids coll-ids :sentinel sentinel :props []}
     (range layers))))

(defn- build-three-layer-cons*-with-accessor []
  (let [{:keys [net head-ids coll-ids props]} (build-nested-with-cons* 3)
        [coll0 coll1 coll2] coll-ids
        [head0 head1 head2] head-ids
        out (new-node-id)
        n (install-cell net out)
        [n tasks] (reduce
                   (fn [[n tasks] installer]
                     (install-prop! n tasks installer))
                   [n tq/empty-queue]
                   [(obj/p:cdr* coll1 coll0)
                    (obj/p:cdr* coll2 coll1)
                    (obj/p:car* out coll2)])]
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
    (let [{:keys [net parent coll prop-id]} (build-slot-net obj/p:car*)
          n' (-> net
                 (seed-cell parent 10)
                 (run-props [prop-id]))
          coll-net (collection-net n' coll)
          dict (net/net-dict-or-empty coll-net)]
      (is (= 10 (slot-strongest coll-net :car)))
      (is (contains? dict parent))
      (is (contains? (get-in dict [:slot-index :car]) parent)))))

(deftest p-car-syncs-collection-slot-out-to-parent
  (testing "collection slot value dispatches through tapped avatar to parent"
    (let [{:keys [net parent coll prop-id]} (build-slot-net obj/p:car*)
          {:keys [exec-net]} (obj/attach-slot-sync (obj/empty-cons-net) :car parent net)
          slot (obj/slot-id exec-net :car)
          coll-value (net/assoc-net-cell exec-net slot (cell/cell 42 42))
          n' (-> net
                 (seed-cell coll coll-value)
                 (run-props [prop-id]))]
      (is (= 42 (strongest n' parent))))))

(deftest p-car-fans-out-slot-update-to-all-indexed-parents
  (testing "one slot run emits messages only for tapped parents updated in the subnet"
    (let [p1 (new-node-id)
          p2 (new-node-id)
          coll (new-node-id)
          n0 (install-cells [p1 p2 coll])
          [prop-id n0] ((obj/p:car* p1 coll) n0)
          n0 (-> n0 (seed-cell p1 value/nothing) (seed-cell p2 value/nothing))
          {:keys [exec-net]} (obj/attach-slot-sync (obj/empty-cons-net) :car p1 n0)
          {:keys [exec-net]} (obj/attach-slot-sync exec-net :car p2 n0)
          slot (obj/slot-id exec-net :car)
          coll-value (net/assoc-net-cell exec-net slot (cell/cell 77 77))
          n' (-> n0
                 (seed-cell coll coll-value)
                 (run-props [prop-id]))]
      (is (= 77 (strongest n' p1)))
      (is (= 77 (strongest n' p2))))))

(deftest p-car-reuses-existing-avatar
  (testing "repeated equivalent parent updates reuse the same avatar and sync props"
    (let [{:keys [net parent coll prop-id]} (build-slot-net obj/p:car*)
          n1 (-> net (seed-cell parent 1) (run-props [prop-id]))
          coll-net1 (collection-net n1 coll)
          avatar1 (get (net/net-dict-or-empty coll-net1) parent)
          sync-keys1 (sync-prop-keys coll-net1)
          n2 (-> n1 (seed-cell parent 1) (run-props [prop-id]))
          coll-net2 (collection-net n2 coll)
          avatar2 (get (net/net-dict-or-empty coll-net2) parent)
          sync-keys2 (sync-prop-keys coll-net2)]
      (is (= avatar1 avatar2))
      (is (= sync-keys1 sync-keys2))
      (is (= 1 (slot-strongest coll-net2 :car))))))

(deftest p-car-creates-missing-slot-before-attach
  (testing "slot sync can establish a missing slot cell"
    (let [{:keys [net parent coll prop-id]} (build-slot-net obj/p:car*)
          sparse (net/net-with-dict net/empty-net {:slot-index {}})
          n' (-> net
                 (seed-cell parent 5)
                 (seed-cell coll sparse)
                 (run-props [prop-id]))
          coll-net (collection-net n' coll)]
      (is (obj/slot-id coll-net :car))
      (is (= 5 (slot-strongest coll-net :car))))))

(deftest p-car-accepts-subsuming-named-network-update
  (testing "subsuming named-network values replace weaker slot evidence"
    (let [{:keys [net parent coll prop-id]} (build-slot-net obj/p:car*)
          weak (named-cell-net [[:x true]])
          strong (add-named-cell weak :y false)
          n1 (-> net (seed-cell parent weak) (run-props [prop-id]))
          n2 (-> n1 (seed-cell parent strong) (run-props [prop-id]))
          coll-net (collection-net n2 coll)]
      (is (= #{strong} (slot-content coll-net :car)))
      (is (= strong (slot-strongest coll-net :car))))))

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
          n0 (install-cells [parent coll])
          [prop-id n0] ((obj/p:car* parent coll) n0)
          coll-net (obj/empty-cons-net)
          n0 (net/assoc-net-cell n0 coll (cell/cell coll-net coll-net))
          [tasks _n'] (core/eval-cell coll (message coll coll-net) n0)]
      (is (prop/prop? (net/network-lookup-propagator n0 prop-id)))
      (is (tq/queue-empty? tasks)))))

(deftest p-cons-syncs-car-and-cdr-independently
  (testing "p:cons* installs independent bidirectional slot constraints"
    (let [head (new-node-id)
          tail (new-node-id)
          coll (new-node-id)
          n (install-cells [head tail coll])
          [[car-prop cdr-prop] n] ((obj/p:cons* head tail coll) n)
          n' (-> n
                 (seed-cell head 10)
                 (seed-cell tail 20)
                 (run-props [car-prop cdr-prop]))
          coll-net (collection-net n' coll)]
      (is (= 10 (slot-strongest coll-net :car)))
      (is (= 20 (slot-strongest coll-net :cdr))))))

(deftest compare-new-slot-sync-with-current-linked-list-local-case
  (testing "new one-layer slot sync exposes the same local car/cdr values as old p:cons"
    (let [head (new-node-id)
          tail (new-node-id)
          coll (new-node-id)
          old-net (install-cells [head tail coll])
          [[car-prop cdr-prop linked-prop] old-net] ((linked/p:cons-scheduled head tail coll) old-net)
          old-net (-> old-net (seed-cell head 10) (seed-cell tail 20))
          old-net (run-props old-net [car-prop cdr-prop linked-prop])
          old-content (content old-net coll)
          old-subnet (state/state-subnet old-content)
          old-dict (net/net-dict-or-empty old-subnet)
          new-head (new-node-id)
          new-tail (new-node-id)
          new-coll (new-node-id)
          new-net (install-cells [new-head new-tail new-coll])
          [[car-prop cdr-prop] new-net] ((obj/p:cons* new-head new-tail new-coll) new-net)
          new-net (-> new-net
                      (seed-cell new-head 10)
                      (seed-cell new-tail 20)
                      (run-props [car-prop cdr-prop]))
          new-coll-net (collection-net new-net new-coll)]
      (is (= 10 (net/network-cell-strongest old-subnet (get old-dict head))))
      (is (= 20 (net/network-cell-strongest old-subnet (get old-dict tail))))
      (is (= 10 (slot-strongest new-coll-net :car)))
      (is (= 20 (slot-strongest new-coll-net :cdr))))))

(deftest p-cons*-nested-local-layer-syncs-index-two-head
  (testing "p:cons* x5: seeding head2 syncs the local coll2 :car slot"
    (let [{:keys [net head-ids coll-ids props]} (build-nested-with-cons* 5)
          head2 (nth head-ids 2)
          coll2 (nth coll-ids 2)
          n' (-> net
                 (seed-cell head2 30)
                 (run-props props))
          coll2-net (collection-net n' coll2)]
      (is (= 30 (strongest n' head2)))
      (is (= 30 (slot-strongest coll2-net :car))))))

(deftest p-cons*-three-layer-accessor-head2-reaches-out
  (testing "new slot-index model supports (car (cdr (cdr coll0))) accessor fan-out"
    (let [{:keys [net tasks head2 out]} (build-three-layer-cons*-with-accessor)
          [n tasks] (seed-cell! net tasks head2 30)
          n' (run-queue n tasks)]
      (is (= 30 (strongest n' out))))))

(deftest p-cons*-three-layer-accessor-three-heads-reaches-out
  (testing "new slot-index model reaches accessor out with all heads seeded"
    (let [{:keys [net tasks head0 head1 head2 out]} (build-three-layer-cons*-with-accessor)
          [n tasks] (seed-cell! net tasks head0 10)
          [n tasks] (seed-cell! n tasks head1 20)
          [n tasks] (seed-cell! n tasks head2 30)
          n' (run-queue n tasks)]
      (is (= 10 (strongest n' head0)))
      (is (= 30 (strongest n' out))))))

(deftest collection-value-does-not-persist-effect-taps
  (testing "collection named-network may store declarative sync structure, but not effect taps"
    (let [{:keys [net parent coll prop-id]} (build-slot-net obj/p:car*)
          n' (-> net
                 (seed-cell parent 10)
                 (run-props [prop-id]))
          coll-net (collection-net n' coll)]
      (is (empty? (tap-prop-keys coll-net))
          "effect tap ids are activation-local and should not persist in collection content")
      (is (empty? (effect-tap-prop-entries coll-net))
          "collection content should not retain effect tap propagator entries"))))

(deftest no-linked-list-dispatch-required
  (testing "p:cons* installs only car/cdr slot sync propagators"
    (let [head (new-node-id)
          tail (new-node-id)
          coll (new-node-id)
          n (install-cells [head tail coll])
          [[car-prop cdr-prop] n] ((obj/p:cons* head tail coll) n)]
      (is (= 2 (count (filter prop/prop? (vals (net/net-env n))))))
      (is (prop/prop? (net/network-lookup-propagator n car-prop)))
      (is (prop/prop? (net/network-lookup-propagator n cdr-prop))))))
