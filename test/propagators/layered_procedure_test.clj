(ns propagators.layered-procedure-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.closure :as closure]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.debugger :as debugger]
            [propagators.ids :refer [new-node-id]]
            [propagators.layered :as layered]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.named-network :as named]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.arithmetic.base :as base]
            [propagators.stdlib.arithmetic.provenance :as provenance]
            [propagators.stdlib.layered :as layered-ops]
            [propagators.stdlib.provenance-arithmetic :as prov-arith]
            [propagators.stdlib.prop :as stdlib-prop]))

(defn- install-empty-cells
  [network ids]
  (reduce nb/install-cell network ids))

(defn- install-propagators
  [network installers]
  (reduce (fn [[ids current] installer]
            (let [[id next-network] (nb/install-propagator current installer)]
              [(conj ids id) next-network]))
          [[] network]
          installers))

(defn- new-layered-call
  [n]
  (let [[a b out] (repeatedly 3 new-node-id)]
    {:net (install-empty-cells n [a b out])
     :a a :b b :out out}))

(defn- new-output-cell
  [n]
  (let [out (new-node-id)]
    {:net (nb/install-cell n out)
     :out out}))

(defn- install-layered-apply
  [n proc a b out]
  (let [[prop-id network]
        (nb/install-propagator n (layered/p:apply-layered proc [a b] out))]
    {:net network :prop prop-id}))

(defn- install-operator-apply
  [n _operator-symbol operator a b out]
  (let [[prop-id network] (nb/install-propagator n (operator a b out))]
    {:net network :prop prop-id}))

(defn- units-closure-value []
  (closure/closure
   (fn [_closure-net input-ids output-ids network]
     (let [[_current _arg-a _arg-b] input-ids
           [out] output-ids]
       (second
        (nb/install-propagator
         network
         ((prop/primitive-propagator (fn [& _] :unitless))
          _current _arg-a _arg-b out)))))
   net/empty-net))

(defn- install-procedure-layer-value
  [n proc layer-name closure-value]
  (let [closure-id (new-node-id)
        n0 (nb/install-cell n closure-id closure-value closure-value)]
    (layered/install-layered-procedure! n0 proc layer-name closure-id)))

(defn- nested-layered-closure
  [inner-net inner-operator]
  (closure/closure
   (fn [closure-net input-ids output-ids network]
     (let [[left-id right-id] input-ids
           [out-id] output-ids
           inner-out-id (new-node-id)
           n0 (named/join network closure-net)
           n1 (nb/install-cell n0 inner-out-id)
           [_ n2] ((inner-operator left-id right-id inner-out-id) n1)
           [_ n3] ((layered/p:base out-id inner-out-id) n2)]
       n3))
   inner-net))

(defn- install-layered-inputs
  [n a b]
  (let [[a-base a-prov b-base b-prov] (repeatedly 4 new-node-id)
        prepared (install-empty-cells n [a-base a-prov b-base b-prov])
        [slot-props network]
        (install-propagators
         prepared
         [(layered/p:base a-base a)
          (layered/p:layer :provenance a-prov a)
          (layered/p:base b-base b)
          (layered/p:layer :provenance b-prov b)])]
    {:net network :slot-props slot-props
     :a-base a-base :a-prov a-prov :b-base b-base :b-prov b-prov}))

(defn- install-provenance-inputs
  [n a b]
  (let [[a-prov b-prov] (repeatedly 2 new-node-id)
        prepared (install-empty-cells n [a-prov b-prov])
        [slot-props network]
        (install-propagators
         prepared
         [(layered/p:layer :provenance a-prov a)
          (layered/p:layer :provenance b-prov b)])]
    {:net network :slot-props slot-props :a-prov a-prov :b-prov b-prov}))

(defn- seed-layered-inputs
  [n {:keys [a-base a-prov b-base b-prov]} a-value a-provenance b-value b-provenance]
  (-> n
      (nb/seed-cell a-base a-value)
      (nb/seed-cell a-prov a-provenance)
      (nb/seed-cell b-base b-value)
      (nb/seed-cell b-prov b-provenance)))

(defn- install-base-inputs
  [n a b]
  (let [[a-base b-base] (repeatedly 2 new-node-id)
        prepared (install-empty-cells n [a-base b-base])
        [slot-props network]
        (install-propagators prepared [(layered/p:base a-base a)
                                       (layered/p:base b-base b)])]
    {:net network :slot-props slot-props :a-base a-base :b-base b-base}))

(defn- seed-base-inputs
  [n {:keys [a-base b-base]} a-value b-value]
  (-> n
      (nb/seed-cell a-base a-value)
      (nb/seed-cell b-base b-value)))

(defn- run-layered-application
  [n install-apply a-value a-provenance b-value b-provenance]
  (let [call (new-layered-call n)
        {:keys [net a b out]} call
        input (install-layered-inputs net a b)
        apply (install-apply (:net input) a b out)
        n' (-> (:net apply)
               (seed-layered-inputs input a-value a-provenance b-value b-provenance)
               (nb/run-propagators (conj (:slot-props input) (:prop apply))))]
    {:net n'
     :a a
     :b b
     :out out
     :apply-prop (:prop apply)
     :input input
     :slot-props (:slot-props input)
     :out-object (net/network-cell-value n' out)}))

(defn- run-base-only-application
  [n proc a-value b-value]
  (let [call (new-layered-call n)
        {:keys [net a b out]} call
        input (install-base-inputs net a b)
        apply (install-layered-apply (:net input) proc a b out)
        n' (-> (:net apply)
               (seed-base-inputs input a-value b-value)
               (nb/run-propagators (conj (:slot-props input) (:prop apply))))]
    {:net n'
     :a a
     :b b
     :out out
     :apply-prop (:prop apply)
     :input input
     :slot-props (:slot-props input)
     :out-object (net/network-cell-value n' out)}))

(defn- run-operator-on-existing-inputs
  [n operator a b out]
  (let [apply (install-operator-apply n 'layered/+ operator a b out)
        n' (nb/run-propagators (:net apply) [(:prop apply)])]
    {:net n'
     :out out
     :out-object (net/network-cell-value n' out)}))

(defn- procedure-object
  [n proc]
  (net/network-cell-value n proc))

(defn- assert-layer
  [object layer expected]
  (is (= expected (obj/slot-strongest object layer))))

(defn- assert-missing-layer
  [object layer]
  (is (nil? (net/network-dict-entry object layer))))

;; --- Default path: full procedure (base + provenance), layered/+ - / //

(deftest apply-layered-computes-base-and-provenance
  (testing "layered apply with pre-installed base and provenance"
    (let [{:keys [net proc]} (prov-arith/+ net/empty-net)
          result (run-layered-application
                  net
                  #(install-layered-apply %1 proc %2 %3 %4)
                  10 #{:a}
                  20 #{:b})]
      (assert-layer (:out-object result) :base 30)
      (assert-layer (:out-object result) :provenance #{:a :b}))))

(deftest layered-apply-bypasses-base-on-provenance-contradiction
  (let [{:keys [net proc]} (prov-arith/+ net/empty-net)
        contradiction (value/contradiction-with-provenance #{:event/conflict})
        result (run-layered-application
                net
                #(install-layered-apply %1 proc %2 %3 %4)
                contradiction #{:argument/a}
                20 #{:argument/b})
        output (:out-object result)]
    (is (value/contradiction? output))
    (is (= #{:event/conflict :argument/a :argument/b}
           (value/contradiction-provenance output)))))

(deftest layered-contradiction-unions-scoped-operator-provenance
  (let [{:keys [net proc]} (prov-arith/+ net/empty-net)
        proc-value (#'layered/materialize-layered-cell-value net proc)
        scoped-proc-id (new-node-id)
        operator-token {:provenance/type :lexical-access
                        :lookup/key :test/conflicting-operator
                        :scope/source :child
                        :scope/chain [:root :child]}
        scoped-proc (scope-source/scope-value :child
                                              nil
                                              [:root :child]
                                              proc-value
                                              #{operator-token})
        n0 (nb/install-cell net scoped-proc-id scoped-proc scoped-proc)
        contradiction (value/contradiction-with-provenance #{:input/conflict})
        result (run-base-only-application n0 scoped-proc-id contradiction 5)]
    (is (= #{:input/conflict operator-token}
           (value/contradiction-provenance (:out-object result))))))

(deftest apply-layered-carries-scope-provenance
  (testing "scope provenance joins the existing provenance layer"
    (let [{base-net :net proc :proc} (prov-arith/+ net/empty-net)
          {call-net :net a :a b :b out :out} (new-layered-call base-net)
          apply (install-layered-apply call-net proc a b out)
          token {:provenance/type :lexical-access
                 :lookup/key :test/a
                 :scope/source :child
                 :scope/chain [:root :child]}
          a-value (scope-source/scope-value :child nil [:root :child] 10 #{token})
          n (-> (:net apply)
                (nb/seed-cell a a-value)
                (nb/seed-cell b 20)
                (nb/run-propagators [(:prop apply)]))
          out-value (net/network-cell-value n out)]
      (assert-layer out-value :base 30)
      (assert-layer out-value :provenance #{token}))))

(deftest apply-layered-carries-scoped-operator-provenance
  (testing "scope metadata on the procedure contributes provenance, not branches"
    (let [{:keys [net proc]} (prov-arith/+ net/empty-net)
          proc-value (#'layered/materialize-layered-cell-value net proc)
          scoped-proc-id (new-node-id)
          token {:provenance/type :lexical-access
                 :lookup/key :test/operator
                 :scope/source :child
                 :scope/chain [:root :child]}
          scoped-proc (scope-source/scope-value :child
                                                nil
                                                [:root :child]
                                                proc-value
                                                #{token})
          n0 (nb/install-cell net scoped-proc-id scoped-proc scoped-proc)
          result (run-base-only-application n0 scoped-proc-id 4 5)]
      (assert-layer (:out-object result) :base 9)
      (assert-layer (:out-object result) :provenance #{token})
      (assert-missing-layer (:out-object result) :scope/source))))

(deftest debugger-reports-layered-dispatch
  (testing "layered debugger reports layer branch results and selected value"
    (let [events (atom [])
          {:keys [net proc]} (prov-arith/+ net/empty-net)]
      (try
        (debugger/set-sink! #(swap! events conj %))
        (debugger/enable!)
        (run-layered-application
         net
         #(install-layered-apply %1 proc %2 %3 %4)
         10 #{:a}
         20 #{:b})
        (let [layer-events (filter #(= :layered/layer (:event %)) @events)
              selected-event (first (filter #(= :layered/selected (:event %)) @events))
              by-layer (into {} (map (juxt :layer :handler-result)) layer-events)]
          (is (= 30 (:base by-layer)))
          (is (= #{:a :b} (:provenance by-layer)))
          (is (= 30 (obj/slot-value (:selected-value selected-event) :base)))
          (is (= #{:a :b}
                 (obj/slot-value (:selected-value selected-event) :provenance))))
        (finally
          (debugger/disable!)
          (debugger/reset-sink!))))))

(deftest apply-layered-minus-computes-base-and-provenance
  (testing "layered/- with pre-installed base and provenance"
    (let [{:keys [net operator]} (prov-arith/- net/empty-net)
          result (run-layered-application
                  net
                  #(install-operator-apply %1 'layered/- operator %2 %3 %4)
                  30 #{:a}
                  12 #{:b})]
      (assert-layer (:out-object result) :base 18)
      (assert-layer (:out-object result) :provenance #{:a :b}))))

(deftest apply-layered-times-computes-base-and-provenance
  (testing "layered/* with pre-installed base and provenance"
    (let [{:keys [net operator]} (prov-arith/* net/empty-net)
          result (run-layered-application
                  net
                  #(install-operator-apply %1 'layered/* operator %2 %3 %4)
                  6 #{:a}
                  7 #{:b})]
      (assert-layer (:out-object result) :base 42)
      (assert-layer (:out-object result) :provenance #{:a :b}))))

(deftest apply-layered-divide-computes-base-and-provenance
  (testing "layered// with pre-installed base and provenance"
    (let [{:keys [net operator]} (prov-arith// net/empty-net)
          result (run-layered-application
                  net
                  #(install-operator-apply %1 'layered// operator %2 %3 %4)
                  60 #{:a}
                  12 #{:b})]
      (assert-layer (:out-object result) :base 5)
      (assert-layer (:out-object result) :provenance #{:a :b}))))

(deftest skips-non-base-layer-when-args-do-not-have-it
  (testing "provenance branch skipped when arguments have no provenance layer"
    (let [{:keys [net proc]} (prov-arith/+ net/empty-net)
          result (run-base-only-application net proc 7 8)]
      (assert-layer (:out-object result) :base 15)
      (assert-missing-layer (:out-object result) :provenance))))

;; --- Special case: reactive `install-layered-procedure!` / late layers

(deftest layered-procedure-attachment-is-a-slot-propagator
  (testing "raw layered procedure attachment declares topology without materializing"
    (let [proc (new-node-id)
          closure-id (new-node-id)
          closure-value (units-closure-value)
          n0 (nb/install-cells [proc closure-id])
          [slot-prop n1] ((layered/p:layered-procedure :units closure-id proc) n0)
          n2 (nb/seed-cell n1 closure-id closure-value)
          n3 (nb/run-propagators n2 [slot-prop])]
      (is (nil? (obj/slot-value (procedure-object n2 proc) :units)))
      (is (obj/accessor-network? (procedure-object n3 proc)))
      (is (contains? (obj/accessor-slot-keys (procedure-object n3 proc)) :units))
      (is (nil? (obj/slot-strongest (procedure-object n3 proc) :units))))))

(deftest layered-procedure-builds-and-extends-slot-object
  (testing "reactive extension declares topology without materializing procedure state"
    (let [{:keys [net proc]} (prov-arith/+ net/empty-net)
          extended (install-procedure-layer-value net
                                                  proc
                                                  :units
                                                  (units-closure-value))
          declarations (obj/accessor-declarations-for (:net extended) proc)
          visible-net (nb/run-propagators (:net extended) [(:prop extended)])]
      (is (contains? declarations :base))
      (is (contains? declarations :provenance))
      (is (contains? declarations :units))
      (is (nil? (obj/slot-strongest (procedure-object net proc) :base)))
      (is (nil? (obj/slot-strongest (procedure-object net proc) :provenance)))
      (is (nil? (obj/slot-strongest (procedure-object (:net extended) proc) :units)))
      (is (obj/accessor-network? (procedure-object visible-net proc)))
      (is (contains? (obj/accessor-slot-keys (procedure-object visible-net proc))
                     :units))
      (is (nil? (obj/slot-strongest (procedure-object visible-net proc) :units))))))

(deftest layered-procedure-layer-before-procedure-cell-is-visible-on-apply
  (testing "layer declaration can create the procedure cell lazily"
    (let [proc (new-node-id)
          closure-id (new-node-id)
          n0 (nb/install-cell net/empty-net
                              closure-id
                              base/plus-closure
                              base/plus-closure)
          installed (layered/install-layered-procedure! n0 proc :base closure-id)
          result (run-base-only-application (:net installed) proc 4 5)]
      (assert-layer (:out-object result) :base 9))))

(deftest nested-layered-procedure-dispatch-is-materialized-during-outer-apply
  (testing "outer layered procedure can use an inner layered operator declared only as topology"
    (let [{inner-net :net inner-operator :operator}
          (prov-arith/+ net/empty-net {:provenance? false})
          proc (new-node-id)
          closure-value (nested-layered-closure inner-net inner-operator)
          installed (install-procedure-layer-value net/empty-net
                                                   proc
                                                   :base
                                                   closure-value)
          result (run-base-only-application (:net installed) proc 8 9)]
      (assert-layer (:out-object result) :base 17))))

(deftest layered-operator-reuses-and-observes-procedure-extension
  (testing "layered/+ defined on base-only proc; provenance added later still affects re-apply"
    (let [{:keys [net proc operator]} (prov-arith/+ net/empty-net {:provenance? false})
          first-result (run-layered-application
                        net
                        #(install-operator-apply %1 'layered/+ operator %2 %3 %4)
                        1 #{:a}
                        2 #{:b})
          extended (install-procedure-layer-value (:net first-result)
                                                  proc
                                                  :provenance
                                                  provenance/+)
          output (new-output-cell (:net extended))
          out2 (:out output)
          second-result (run-operator-on-existing-inputs
                         (:net output)
                         operator
                         (:a first-result)
                         (:b first-result)
                         out2)]
      (assert-layer (:out-object first-result) :base 3)
      (assert-missing-layer (:out-object first-result) :provenance)
      (assert-layer (:out-object second-result) :base 3)
      (assert-layer (:out-object second-result) :provenance #{:a :b}))))

(deftest installed-layered-application-observes-late-procedure-layer
  (testing "later input slot updates let an installed application observe late procedure layers"
    (let [{:keys [net proc operator]} (prov-arith/+ net/empty-net {:provenance? false})
          first-result (run-base-only-application net proc 1 2)
          extended (install-procedure-layer-value (:net first-result)
                                                  proc
                                                  :provenance
                                                  provenance/+)
          provenance-input (install-provenance-inputs (:net extended)
                                                      (:a first-result)
                                                      (:b first-result))
          refired-net (-> (:net provenance-input)
                          (nb/seed-cell (:a-prov provenance-input) #{:a})
                          (nb/seed-cell (:b-prov provenance-input) #{:b})
                          (nb/run-propagators (:slot-props provenance-input)))
          out-object (net/network-cell-value refired-net (:out first-result))]
      (assert-layer (:out-object first-result) :base 3)
      (assert-missing-layer (:out-object first-result) :provenance)
      (assert-layer out-object :base 3)
      (assert-layer out-object :provenance #{:a :b}))))
