(ns propagators.layered-procedure-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.closure :as closure]
            [propagators.compile :as compile]
            [propagators.datastructures.compound-object :as obj]
            [propagators.debugger :as debugger]
            [propagators.ids :refer [new-node-id]]
            [propagators.layered :as layered]
            [propagators.datastructures.named-network :as named]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.arithmetic.base :as base]
            [propagators.stdlib.arithmetic.provenance :as provenance]
            [propagators.stdlib.layered :as layered-ops]
            [propagators.stdlib.provenance-arithmetic :as prov-arith]
            [propagators.stdlib.prop :as stdlib-prop]))

(def ^:private layered-installers
  {'layered/p:base layered/p:base
   'layered/p:layer layered/p:layer
   'layered/p:layered-procedure layered/p:layered-procedure
   'layered/p:apply-layered2 (fn [proc a b out]
                               (layered/p:apply-layered proc [a b] out))
   'prop/+ stdlib-prop/+
   'prop// stdlib-prop//
   'layered/+ layered-ops/+
   'layered/- layered-ops/-
   'layered/* layered-ops/*
   'layered// layered-ops//})

(defn- layered-ctx [n sym->value expr]
  (compile/eval-layered n layered-installers sym->value expr))

(defn- new-layered-call
  [n]
  (let [ctx (layered-ctx n {} '(let-cell [a b out] out))]
    {:net (:net ctx)
     :a (compile/cell-ref ctx 'a)
     :b (compile/cell-ref ctx 'b)
     :out (compile/cell-ref ctx 'out)}))

(defn- new-output-cell
  [n]
  (let [ctx (layered-ctx n {} '(let-cell [out] out))]
    {:net (:net ctx)
     :out (compile/cell-ref ctx 'out)}))

(defn- install-layered-apply
  [n proc a b out]
  (let [ctx (layered-ctx
             n
             {'proc proc 'a a 'b b 'out out}
             '(layered/p:apply-layered2 proc a b out))]
    {:net (:net ctx)
     :prop (first (:props ctx))}))

(defn- install-operator-apply
  [n operator-symbol operator a b out]
  (let [ctx (compile/eval-layered
             (compile/bind-vars n {'a a 'b b 'out out})
             (assoc layered-installers operator-symbol operator)
             {}
             (list operator-symbol 'a 'b 'out))]
    {:net (:net ctx)
     :prop (first (:props ctx))}))

(defn- units-closure-value []
  (closure/closure
   (fn [_closure-net input-ids output-ids network]
     (let [[_current _arg-a _arg-b] input-ids
           [out] output-ids]
       (:net
        (compile/eval-layered
         network
         {'p:unitless (prop/primitive-propagator (fn [& _] :unitless))}
         {'current _current
          'arg-a _arg-a
          'arg-b _arg-b
          'out out}
         '(p:unitless current arg-a arg-b out)))))
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
  (let [ctx (layered-ctx
             n
             {'a a 'b b}
             '(let-cell [a-base a-prov b-base b-prov]
                (layered/p:base a-base a)
                (layered/p:layer :provenance a-prov a)
                (layered/p:base b-base b)
                (layered/p:layer :provenance b-prov b)))]
    {:net (:net ctx)
     :slot-props (:props ctx)
     :a-base (compile/cell-ref ctx 'a-base)
     :a-prov (compile/cell-ref ctx 'a-prov)
     :b-base (compile/cell-ref ctx 'b-base)
     :b-prov (compile/cell-ref ctx 'b-prov)}))

(defn- install-provenance-inputs
  [n a b]
  (let [ctx (layered-ctx
             n
             {'a a 'b b}
             '(let-cell [a-prov b-prov]
                (layered/p:layer :provenance a-prov a)
                (layered/p:layer :provenance b-prov b)))]
    {:net (:net ctx)
     :slot-props (:props ctx)
     :a-prov (compile/cell-ref ctx 'a-prov)
     :b-prov (compile/cell-ref ctx 'b-prov)}))

(defn- seed-layered-inputs
  [n {:keys [a-base a-prov b-base b-prov]} a-value a-provenance b-value b-provenance]
  (:net
   (layered-ctx
    n
    {'a-base a-base
     'a-prov a-prov
     'b-base b-base
     'b-prov b-prov
     'a-value a-value
     'a-provenance a-provenance
     'b-value b-value
     'b-provenance b-provenance}
    '(do
       (seed a-base a-value)
       (seed a-prov a-provenance)
       (seed b-base b-value)
       (seed b-prov b-provenance)))))

(defn- install-base-inputs
  [n a b]
  (let [ctx (layered-ctx
             n
             {'a a 'b b}
             '(let-cell [a-base b-base]
                (layered/p:base a-base a)
                (layered/p:base b-base b)))]
    {:net (:net ctx)
     :slot-props (:props ctx)
     :a-base (compile/cell-ref ctx 'a-base)
     :b-base (compile/cell-ref ctx 'b-base)}))

(defn- seed-base-inputs
  [n {:keys [a-base b-base]} a-value b-value]
  (:net
   (layered-ctx
    n
    {'a-base a-base
     'b-base b-base
     'a-value a-value
     'b-value b-value}
    '(do
       (seed a-base a-value)
       (seed b-base b-value)))))

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
