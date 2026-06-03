(ns propagators-layered-procedure-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :refer [new-node-id]]
            [propagators.layered :as layered]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- plus-base-closure-value []
  (closure/closure
   (fn [_closure-net input-ids output-ids network]
     (let [[a b] input-ids
           [out] output-ids]
       (second (((prop/primitive-propagator +) a b out) network))))
   net/empty-net))

(defn- plus-provenance-closure-value []
  (closure/closure
   (fn [_closure-net input-ids output-ids network]
     (let [[current arg-a arg-b] input-ids
           [out] output-ids
           a-prov (new-node-id)
           b-prov (new-node-id)
           n1 (reduce nb/install-cell network [a-prov b-prov])
           [_ n2] ((layered/p:layer :provenance a-prov arg-a) n1)
           [_ n3] ((layered/p:layer :provenance b-prov arg-b) n2)
           [_ n4] (((prop/primitive-propagator
                     (fn [cur pa pb]
                       (if (and (set? pa) (set? pb))
                         (set/union
                          (if (set? cur) cur #{})
                          pa
                          pb)
                         value/nothing)))
                    current a-prov b-prov out)
                   n3)]
       n4))
   net/empty-net))

(defn- units-closure-value []
  (closure/closure
   (fn [_closure-net input-ids output-ids network]
     (let [[_current _arg-a _arg-b] input-ids
           [out] output-ids]
       (second (((prop/primitive-propagator (fn [& _] :unitless)) _current _arg-a _arg-b out)
                network))))
   net/empty-net))

(defn- procedure-extension
  [layer closure-value]
  (nb/named-cell-net [[layer closure-value]]))

(defn- install-plus-procedure
  ([]
   (install-plus-procedure {:provenance? true}))
  ([{:keys [provenance?] :or {provenance? true}}]
   (let [proc (new-node-id)
         base-extension (new-node-id)
         prov-extension (new-node-id)
         n0 (nb/install-cells (cond-> [proc base-extension]
                                provenance? (conj prov-extension)))
         [base-prop n1] ((layered/p:layered-procedure proc base-extension) n0)
         [prov-prop n2] (if provenance?
                          ((layered/p:layered-procedure proc prov-extension) n1)
                          [nil n1])
         n3 (nb/seed-cell n2 base-extension (procedure-extension :base (plus-base-closure-value)))
         n3 (if provenance?
              (nb/seed-cell n3 prov-extension (procedure-extension :provenance (plus-provenance-closure-value)))
              n3)
         procedure-props (cond-> [base-prop] provenance? (conj prov-prop))
         n4 (nb/run-propagators n3 procedure-props)]
     {:net n4
      :proc proc
      :base-extension base-extension
      :prov-extension (when provenance? prov-extension)
      :procedure-props procedure-props})))

(defn- install-layered-inputs
  [n a b]
  (let [a-base (new-node-id)
        a-prov (new-node-id)
        b-base (new-node-id)
        b-prov (new-node-id)
        n1 (reduce nb/install-cell n [a-base a-prov b-base b-prov])
        [a-base-prop n2] ((layered/p:base a-base a) n1)
        [a-prov-prop n3] ((layered/p:layer :provenance a-prov a) n2)
        [b-base-prop n4] ((layered/p:base b-base b) n3)
        [b-prov-prop n5] ((layered/p:layer :provenance b-prov b) n4)]
    {:net n5
     :slot-props [a-base-prop a-prov-prop b-base-prop b-prov-prop]
     :a-base a-base
     :a-prov a-prov
     :b-base b-base
     :b-prov b-prov}))

(deftest layered-procedure-builds-and-extends-slot-object
  (testing "p:layered-procedure merges pure extension fragments"
    (let [{:keys [net proc]} (install-plus-procedure)
          proc-object (net/network-cell-value net proc)
          units-extension (new-node-id)
          n1 (nb/install-cell net units-extension)
          [units-prop n2] ((layered/p:layered-procedure proc units-extension) n1)
          n3 (-> n2
                 (nb/seed-cell units-extension (procedure-extension :units (units-closure-value)))
                 (nb/run-propagators [units-prop]))
          proc-object' (net/network-cell-value n3 proc)]
      (is (obj/slot-strongest proc-object :base))
      (is (obj/slot-strongest proc-object :provenance))
      (is (obj/slot-strongest proc-object' :units)))))

(deftest apply-layered-computes-base-and-provenance
  (testing "p:apply-layered applies closure slots and writes output slots"
    (let [{:keys [net proc]} (install-plus-procedure)
          a (new-node-id)
          b (new-node-id)
          out (new-node-id)
          n0 (reduce nb/install-cell net [a b out])
          {:keys [net slot-props a-base a-prov b-base b-prov]} (install-layered-inputs n0 a b)
          [apply-prop n1] ((layered/p:apply-layered proc [a b] out) net)
          n2 (-> n1
                 (nb/seed-cell a-base 10)
                 (nb/seed-cell a-prov #{:a})
                 (nb/seed-cell b-base 20)
                 (nb/seed-cell b-prov #{:b})
                 (nb/run-propagators (conj slot-props apply-prop)))
          out-object (net/network-cell-value n2 out)]
      (is (= 30 (obj/slot-strongest out-object :base)))
      (is (= #{:a :b} (obj/slot-strongest out-object :provenance))))))

(deftest layered-operator-reuses-and-observes-procedure-extension
  (testing "same operator installer sees later procedure-cell extensions"
    (let [{:keys [net proc]} (install-plus-procedure {:provenance? false})
          p:+ (layered/p:layered-operator proc)
          a (new-node-id)
          b (new-node-id)
          out (new-node-id)
          n0 (reduce nb/install-cell net [a b out])
          {:keys [net slot-props a-base a-prov b-base b-prov]} (install-layered-inputs n0 a b)
          [apply-prop n1] ((p:+ a b out) net)
          n2 (-> n1
                 (nb/seed-cell a-base 1)
                 (nb/seed-cell a-prov #{:a})
                 (nb/seed-cell b-base 2)
                 (nb/seed-cell b-prov #{:b})
                 (nb/run-propagators (conj slot-props apply-prop)))
          out-object (net/network-cell-value n2 out)
          prov-extension (new-node-id)
          out2 (new-node-id)
          n3 (-> n2
                 (nb/install-cell prov-extension)
                 (nb/install-cell out2))
          [prov-prop n4] ((layered/p:layered-procedure proc prov-extension) n3)
          [apply-prop2 n5] ((p:+ a b out2) n4)
          n6 (-> n5
                 (nb/seed-cell prov-extension (procedure-extension :provenance (plus-provenance-closure-value)))
                 (nb/run-propagators [prov-prop apply-prop2]))
          out-object2 (net/network-cell-value n6 out2)]
      (is (= 3 (obj/slot-strongest out-object :base)))
      (is (nil? (net/network-dict-entry out-object :provenance)))
      (is (= 3 (obj/slot-strongest out-object2 :base)))
      (is (= #{:a :b} (obj/slot-strongest out-object2 :provenance))))))

(deftest pure-extension-and-skip-policy
  (testing "ordinary extension fragments model defaults without global mutation"
    (let [{:keys [net proc]} (install-plus-procedure {:provenance? false})
          prov-extension (new-node-id)
          a (new-node-id)
          b (new-node-id)
          out (new-node-id)
          n0 (reduce nb/install-cell net [prov-extension a b out])
          [prov-prop n0] ((layered/p:layered-procedure proc prov-extension) n0)
          {:keys [net slot-props a-base a-prov b-base b-prov]} (install-layered-inputs n0 a b)
          [apply-prop n1] ((layered/p:apply-layered proc [a b] out) net)
          n2 (-> n1
                 (nb/seed-cell prov-extension (procedure-extension :provenance (plus-provenance-closure-value)))
                 (nb/seed-cell a-base 4)
                 (nb/seed-cell a-prov #{:a})
                 (nb/seed-cell b-base 5)
                 (nb/seed-cell b-prov #{:b})
                 (nb/run-propagators (conj slot-props prov-prop apply-prop)))
          out-object (net/network-cell-value n2 out)]
      (is (= 9 (obj/slot-strongest out-object :base)))
      (is (= #{:a :b} (obj/slot-strongest out-object :provenance)))))
  (testing "non-base procedure layer is skipped when no arg has that layer"
    (let [{:keys [net proc]} (install-plus-procedure)
          a (new-node-id)
          b (new-node-id)
          out (new-node-id)
          n0 (reduce nb/install-cell net [a b out])
          a-base (new-node-id)
          b-base (new-node-id)
          n1 (reduce nb/install-cell n0 [a-base b-base])
          [a-base-prop n2] ((layered/p:base a-base a) n1)
          [b-base-prop n3] ((layered/p:base b-base b) n2)
          [apply-prop n4] ((layered/p:apply-layered proc [a b] out) n3)
          n5 (-> n4
                 (nb/seed-cell a-base 7)
                 (nb/seed-cell b-base 8)
                 (nb/run-propagators [a-base-prop b-base-prop apply-prop]))
          out-object (net/network-cell-value n5 out)]
      (is (= 15 (obj/slot-strongest out-object :base)))
      (is (nil? (net/network-dict-entry out-object :provenance))))))
