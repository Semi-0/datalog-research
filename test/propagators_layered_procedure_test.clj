(ns propagators-layered-procedure-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.closure :as closure]
            [propagators.compile :as compile]
            [propagators.datastructures.compound-object :as obj]
            [propagators.layered :as layered]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.arithmetic :as arithmetic]
            [propagators.stdlib.arithmetic.base :as base]
            [propagators.stdlib.arithmetic.provenance :as provenance]
            [propagators.stdlib.layered :as layered-ops]
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

(defn- new-cell
  [n sym]
  (let [ctx (layered-ctx n {} (list 'let-cell [sym] sym))]
    {:net (:net ctx)
     :cell (compile/cell-ref ctx sym)}))

(defn- new-output-cell
  [n]
  (let [ctx (layered-ctx n {} '(let-cell [out] out))]
    {:net (:net ctx)
     :out (compile/cell-ref ctx 'out)}))

(defn- new-plus-procedure-cells
  []
  (let [ctx (layered-ctx
             net/empty-net
             {}
             '(let-cell [proc base-extension prov-extension] proc))]
    {:net (:net ctx)
     :proc (compile/cell-ref ctx 'proc)
     :base-extension (compile/cell-ref ctx 'base-extension)
     :prov-extension (compile/cell-ref ctx 'prov-extension)}))

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

(defn- install-procedure-extension
  [n proc extension extension-value]
  (let [ctx (layered-ctx
             n
             {'proc proc
              'extension extension
              'extension-value extension-value}
             '(do
                (layered/p:layered-procedure proc extension)
                (seed extension extension-value)))]
    {:net (:net ctx)
     :prop (first (:props ctx))}))

(defn- install-plus-procedure
  ([]
   (install-plus-procedure {:provenance? true}))
  ([{:keys [provenance?] :or {provenance? true}}]
   (let [{:keys [net proc base-extension prov-extension]} (new-plus-procedure-cells)
         base (install-procedure-extension
               net
               proc
               base-extension
               (arithmetic/base-extension base/plus-closure))
         prov (when provenance?
                (install-procedure-extension
                 (:net base)
                 proc
                 prov-extension
                 (arithmetic/provenance-extension provenance/+)))
         n3 (if provenance? (:net prov) (:net base))
         base-prop (:prop base)
         prov-prop (:prop prov)
         procedure-props (cond-> [base-prop] provenance? (conj prov-prop))
         n4 (nb/run-propagators n3 procedure-props)]
     {:net n4
      :proc proc
      :base-extension base-extension
      :prov-extension (when provenance? prov-extension)
      :procedure-props procedure-props})))

(defn- install-minus-procedure
  ([]
   (install-minus-procedure {:provenance? true}))
  ([{:keys [provenance?] :or {provenance? true}}]
   (let [{:keys [net proc base-extension prov-extension]} (new-plus-procedure-cells)
         base (install-procedure-extension
               net
               proc
               base-extension
               (arithmetic/minus-base-extension))
         prov (when provenance?
                (install-procedure-extension
                 (:net base)
                 proc
                 prov-extension
                 (arithmetic/minus-provenance-extension)))
         n3 (if provenance? (:net prov) (:net base))
         base-prop (:prop base)
         prov-prop (:prop prov)
         procedure-props (cond-> [base-prop] provenance? (conj prov-prop))
         n4 (nb/run-propagators n3 procedure-props)]
     {:net n4
      :proc proc
      :base-extension base-extension
      :prov-extension (when provenance? prov-extension)
      :procedure-props procedure-props})))

(defn- install-divide-procedure
  ([]
   (install-divide-procedure {:provenance? true}))
  ([{:keys [provenance?] :or {provenance? true}}]
   (let [{:keys [net proc base-extension prov-extension]} (new-plus-procedure-cells)
         base (install-procedure-extension
               net
               proc
               base-extension
               (arithmetic/divide-base-extension))
         prov (when provenance?
                (install-procedure-extension
                 (:net base)
                 proc
                 prov-extension
                 (arithmetic/divide-provenance-extension)))
         n3 (if provenance? (:net prov) (:net base))
         base-prop (:prop base)
         prov-prop (:prop prov)
         procedure-props (cond-> [base-prop] provenance? (conj prov-prop))
         n4 (nb/run-propagators n3 procedure-props)]
     {:net n4
      :proc proc
      :base-extension base-extension
      :prov-extension (when provenance? prov-extension)
      :procedure-props procedure-props})))

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

(defn- extend-procedure-layer
  [n proc extension-name layer closure-value]
  (let [extension (new-cell n extension-name)
        installed (install-procedure-extension
                   (:net extension)
                   proc
                   (:cell extension)
                   (arithmetic/procedure-extension layer closure-value))]
    {:net (nb/run-propagators (:net installed) [(:prop installed)])
     :extension (:cell extension)
     :prop (:prop installed)}))

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
     :out out
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

(deftest layered-procedure-builds-and-extends-slot-object
  (testing "p:layered-procedure merges pure extension fragments"
    (let [{:keys [net proc]} (install-plus-procedure)
          extended (extend-procedure-layer
                    net
                    proc
                    'units-extension
                    :units
                    (units-closure-value))]
      (is (obj/slot-strongest (procedure-object net proc) :base))
      (is (obj/slot-strongest (procedure-object net proc) :provenance))
      (is (obj/slot-strongest (procedure-object (:net extended) proc) :units)))))

(deftest apply-layered-computes-base-and-provenance
  (testing "p:apply-layered applies closure slots and writes output slots"
    (let [{:keys [net proc]} (install-plus-procedure)
          result (run-layered-application
                  net
                  #(install-layered-apply %1 proc %2 %3 %4)
                  10 #{:a}
                  20 #{:b})]
      (assert-layer (:out-object result) :base 30)
      (assert-layer (:out-object result) :provenance #{:a :b}))))

(deftest layered-operator-reuses-and-observes-procedure-extension
  (testing "same operator installer sees later procedure-cell extensions"
    (let [{:keys [net proc]} (install-plus-procedure {:provenance? false})
          p:+ (layered-ops/+ proc)
          first-result (run-layered-application
                        net
                        #(install-operator-apply %1 'layered/+ p:+ %2 %3 %4)
                        1 #{:a}
                        2 #{:b})
          extended (extend-procedure-layer
                    (:net first-result)
                    proc
                    'prov-extension
                    :provenance
                    provenance/+)
          output (new-output-cell (:net extended))
          out2 (:out output)
          second-result (run-operator-on-existing-inputs
                         (:net output)
                         p:+
                         (:a first-result)
                         (:b first-result)
                         out2)]
      (assert-layer (:out-object first-result) :base 3)
      (assert-missing-layer (:out-object first-result) :provenance)
      (assert-layer (:out-object second-result) :base 3)
      (assert-layer (:out-object second-result) :provenance #{:a :b}))))

(deftest ordinary-extension-fragments-model-defaults
  (testing "ordinary extension fragments model defaults without global mutation"
    (let [{:keys [net proc]} (install-plus-procedure {:provenance? false})
          extended (extend-procedure-layer
                    net
                    proc
                    'prov-extension
                    :provenance
                    provenance/+)
          result (run-layered-application
                  (:net extended)
                  #(install-layered-apply %1 proc %2 %3 %4)
                  4 #{:a}
                  5 #{:b})]
      (assert-layer (:out-object result) :base 9)
      (assert-layer (:out-object result) :provenance #{:a :b}))))

(deftest skips-non-base-layer-when-args-do-not-have-it
  (testing "non-base procedure layer is skipped when no arg has that layer"
    (let [{:keys [net proc]} (install-plus-procedure)
          result (run-base-only-application net proc 7 8)]
      (assert-layer (:out-object result) :base 15)
      (assert-missing-layer (:out-object result) :provenance))))

(deftest apply-layered-minus-computes-base-and-provenance
  (testing "layered/- applies minus closure slots and writes output slots"
    (let [{:keys [net proc]} (install-minus-procedure)
          p:- (layered-ops/- proc)
          result (run-layered-application
                  net
                  #(install-operator-apply %1 'layered/- p:- %2 %3 %4)
                  30 #{:a}
                  12 #{:b})]
      (assert-layer (:out-object result) :base 18)
      (assert-layer (:out-object result) :provenance #{:a :b}))))

(deftest apply-layered-divide-computes-base-and-provenance
  (testing "layered// applies divide closure slots and writes output slots"
    (let [{:keys [net proc]} (install-divide-procedure)
          p-div (layered-ops// proc)
          result (run-layered-application
                  net
                  #(install-operator-apply %1 'layered// p-div %2 %3 %4)
                  60 #{:a}
                  12 #{:b})]
      (assert-layer (:out-object result) :base 5)
      (assert-layer (:out-object result) :provenance #{:a :b}))))
