(ns propagators.experimental.runner.amb-shared-slot-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.amb.builder :as builder]
            [experiments.amb-search.cell-slots :as slots]
            [experiments.amb-search.indexed-benchmark :as benchmark]
            [experiments.amb-search.shared-slot-application :as shared]
            [propagators.cell-evaluator :as evaluator]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as compound]
            [propagators.datastructures.compound-object.core :as object]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.layered.procedure :as layer]
            [propagators.layered.runtime :as runtime]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.stdlib.premise-state :as state]
            [propagators.stdlib.supported-procedure :as supported]))

(defn- premise [source timestamp status]
  {:source source :timestamp timestamp :premises-status status})

(defn- evidence [base support]
  (collection/content {:base base :support support :premise-state support}))

(defn- write-slot [network owner slot update]
  (let [[id _] (slots/resolve-slot network owner slot)
        [tasks updated] (evaluator/evaluate (message/message id update) network)]
    (runner/completed-network (runner/run-network tasks updated))))

(deftest paths-share-a-real-cell-with-ordinary-scheduling-and-immutable-history
  (let [[source left right] (repeatedly 3 ids/new-node-id)
        initial (nb/install-cells [source])
        bound (-> initial (slots/bind-slot left :base source) (slots/bind-slot right :base source))
        seen (atom [])
        [_ watched] ((prop/construct-propagator
                      ::watch
                      (fn [_inputs _outputs current]
                        (swap! seen conj (net/network-cell-strongest current source)) [])
                      (mapv #(first (slots/resolve-slot bound % :base)) [left right]) []) bound)
        pa #{(premise source 1 :active)}
        active (write-slot watched left :base (evidence 7 pa))
        snapshot (slots/snapshot active left)
        duplicate (write-slot active right :base (evidence 7 pa))
        retracted (write-slot duplicate right :base
                              (evidence value/nothing #{(premise source 2 :retracted)}))
        recovered (write-slot retracted left :base (evidence 8 #{(premise source 3 :active)}))]
    (is (= (net/net-env initial) (net/net-env bound)))
    (is (= (net/net-graph initial) (net/net-graph bound)))
    (is (= [source source] (mapv #(first (slots/resolve-slot recovered % :base)) [left right])))
    (is (identical? recovered (second (slots/resolve-slot recovered left :base))))
    (is (= 3 (count @seen)) "two aliases and duplicate evidence do not duplicate notifications")
    (is (= pa (datum/support-of (first @seen))))
    (is (value/nothing? (datum/layer-value (second @seen) :base)))
    (is (= #{(premise source 2 :retracted)} (state/states-of (second @seen))))
    (is (= 8 (datum/layer-value (last @seen) :base)))
    (is (= 7 (datum/layer-value (object/slot-value snapshot :base) :base)))
    (is (= pa (datum/support-of (object/slot-value snapshot :base))))
    (is (thrown? clojure.lang.ExceptionInfo (slots/resolve-slot recovered left :missing)))
    (is (thrown? clojure.lang.ExceptionInfo (slots/bind-slot recovered left :base left)))))

(deftest incompatible-slot-rebinding-is-explicit
  (let [[a b owner] (repeatedly 3 ids/new-node-id)
        network (-> (nb/install-cells [a b]) (slots/bind-slot owner :base a))]
    (is (= a (slots/slot-cell-id (slots/bind-slot network owner :base a) owner :base)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rebinding"
                          (slots/bind-slot network owner :base b)))))

(deftest aliases-preserve-equal-version-conflicts-and-recovery
  (let [[source left right] (repeatedly 3 ids/new-node-id)
        initial (-> (nb/install-cells [source])
                    (slots/bind-slot left :value source) (slots/bind-slot right :value source))
        active (write-slot initial left :value (evidence 7 #{(premise source 1 :active)}))
        conflicted (write-slot active right :value
                               (evidence value/nothing #{(premise source 1 :retracted)}))
        frozen (slots/snapshot conflicted left)
        recovered (write-slot conflicted left :value (evidence 8 #{(premise source 2 :active)}))
        [target _] (slots/resolve-slot conflicted right :value)
        conflict (net/network-cell-strongest conflicted target)]
    (is (= #{(premise source 1 :active) (premise source 1 :retracted)} (state/states-of conflict)))
    (is (value/nothing? (datum/layer-value conflict :base)))
    (is (= conflict (object/slot-value frozen :value)))
    (is (= 8 (datum/layer-value (net/network-cell-strongest recovered target) :base)))
    (is (= conflict (object/slot-value (slots/snapshot conflicted right) :value)))))

(deftest a-premised-compound-needs-a-context-bearing-slot-cell
  (let [[owner guarded] (repeatedly 2 ids/new-node-id)
        payload (object/map-compound-object {:x 10})
        raw (object/slot-cell-id payload :x)
        initial (-> (nb/install-cells [owner guarded raw])
                    (net/assoc-net-cell raw (net/network-lookup-cell payload raw))
                    (nb/seed-cell owner (membership/content {:premise-state #{}}))
                    (slots/bind-slot owner :raw-x raw)
                    (slots/bind-slot owner :guarded-x guarded))
        [access installed] ((compound/p:slot :x guarded owner) initial)
        linked (runner/completed-network (runner/run-network [access] installed))
        publish (fn [network update]
                  (let [[tasks updated] (evaluator/evaluate (message/message owner update) network)]
                    (runner/completed-network (runner/run-network tasks updated))))
        active (publish linked (membership/content {:base payload :support #{(premise owner 1 :active)}
                                                   :membership #{}}))
        frozen (slots/snapshot active owner)
        withdrawn (publish active (membership/content {:premise-state #{(premise owner 2 :retracted)}}))
        recovered (publish withdrawn
                           (membership/content {:base (object/map-compound-object {:x 20})
                                                :support #{(premise owner 3 :active)} :membership #{}}))
        read-guarded #(net/network-cell-strongest % (first (slots/resolve-slot % owner :guarded-x)))
        read-raw #(net/network-cell-strongest % (first (slots/resolve-slot % owner :raw-x)))]
    (is (= 10 (read-raw active)))
    (is (= 10 (datum/layer-value (read-guarded active) :base)))
    (is (= #{(premise owner 1 :active)} (datum/support-of (read-guarded active))))
    (is (= 10 (read-raw withdrawn)) "a raw leaf reference alone incorrectly survives parent retraction")
    (is (value/nothing? (datum/layer-value (read-guarded withdrawn) :base)))
    (is (= #{(premise owner 2 :retracted)} (state/states-of (read-guarded withdrawn))))
    (is (= 20 (datum/layer-value (read-guarded recovered) :base)))
    (is (= #{(premise owner 3 :active)} (datum/support-of (read-guarded recovered))))
    (is (= 10 (read-raw recovered)) "replacing a compound can also change the physical leaf cell")
    (is (= 10 (datum/layer-value (object/slot-value frozen :guarded-x) :base)))))

(deftest direct-procedure-slots-preserve-public-layer-views
  (let [[procedure base-id state-id] (repeatedly 3 ids/new-node-id)
        base (layer/base (shared/audited-fixture
                         #(supported/from-primitive (prop/primitive-propagator ::inc inc))))
        prepared (-> (nb/install-cells [procedure])
                     (nb/install-cell base-id base base)
                     (nb/install-cell state-id state/procedure state/procedure))
        base-installed (layered/install-layered-procedure! prepared procedure :base base-id)
        installed (:net (layered/install-layered-procedure! (:net base-installed) procedure :premise-state state-id))
        ordinary (runtime/materialize-layered-cell-value installed procedure)
        direct (shared/with-direct-layer-slots
                 #(runtime/materialize-layered-cell-value installed procedure))]
    (is (= #{:base :premise-state} (object/public-slot-keys direct)))
    (is (= (object/public-slot-keys ordinary) (object/public-slot-keys direct)))
    (doseq [slot (object/public-slot-keys ordinary)]
      (is (= (object/slot-value ordinary slot) (object/slot-value direct slot))))))

(deftest amb-exhaustion-retains-exact-search-state
  (let [calls (atom [])
        predicate (fn [& arguments]
                    (swap! calls conj (vec arguments))
                    (apply builder/pythagorean? arguments))
        built (shared/audited-fixture #(builder/build 3 predicate))
        ordinary (benchmark/sample built :ordinary)
        original-calls @calls]
    (doseq [mode [:ordinary :indexed]
            :let [_ (reset! calls [])
                  actual (shared/with-shared-slots #(benchmark/sample built mode))]]
      (is (nil? (:solution actual)))
      (is (= (:signature ordinary) (:signature actual)))
      (is (= original-calls @calls) "constraint evaluation order and multiplicity are preserved")
      (is (< (get-in actual [:metrics :activations]) (get-in ordinary [:metrics :activations]))))))

(deftest unmarked-applications-retain-the-original-dispatcher
  (let [built (builder/build 2 builder/pythagorean?)
        ordinary (benchmark/sample built :ordinary)
        shared (shared/with-shared-slots #(benchmark/sample built :ordinary))]
    (is (= (:signature ordinary) (:signature shared)))
    (is (= (:metrics ordinary) (:metrics shared)))))
