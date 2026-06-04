(ns propagators-dispatch-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.dispatch :as dispatch]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- installed-cells
  [& ids]
  (reduce nb/install-cell net/empty-net ids))

(deftest filter-forwards-only-when-predicate-is-true
  (testing "truthy predicate forwards input to output"
    (let [pred (ids/new-node-id)
          in (ids/new-node-id)
          out (ids/new-node-id)
          n0 (installed-cells pred in out)
          [filter-prop n1] ((dispatch/p:filter pred in out) n0)
          n2 (-> n1
                 (nb/seed-cell pred true)
                 (nb/seed-cell in :ok)
                 (nb/run-propagators [filter-prop]))]
      (is (= :ok (net/network-cell-strongest n2 out)))))

  (testing "false predicate drops input"
    (let [pred (ids/new-node-id)
          in (ids/new-node-id)
          out (ids/new-node-id)
          n0 (installed-cells pred in out)
          [filter-prop n1] ((dispatch/p:filter pred in out) n0)
          n2 (-> n1
                 (nb/seed-cell pred false)
                 (nb/seed-cell in :ok)
                 (nb/run-propagators [filter-prop]))]
      (is (= :bool4/nothing (net/network-cell-strongest n2 out))))))

(deftest layered-object-policy-copies-result-bank-slots
  (testing "result-bank slots reduce into the output layered object"
    (let [bank (ids/new-node-id)
          out (ids/new-node-id)
          base-value (ids/new-node-id)
          n0 (-> (installed-cells out base-value)
                 (dispatch/install-result-bank bank))
          [bank-slot-prop n1] ((layered/p:layer :base base-value bank) n0)
          [reducer-props n2] ((dispatch/reduce-results
                               (dispatch/layered-object-policy [:base])
                               bank
                               out)
                              n1)
          n3 (-> n2
                 (nb/seed-cell base-value 42)
                 (nb/run-propagators (into [bank-slot-prop] reducer-props)))
          out-object (net/network-cell-strongest n3 out)]
      (is (= 42 (net/network-cell-strongest
                 out-object
                 (net/network-dict-entry out-object :base)))))))

(deftest select-one-policy-supports-generic-style-reduction
  (testing "one usable slot is selected"
    (let [bank (ids/new-node-id)
          default (ids/new-node-id)
          out (ids/new-node-id)
          handler-result (ids/new-node-id)
          n0 (-> (installed-cells default out handler-result)
                 (dispatch/install-result-bank bank)
                 (nb/seed-cell default :default))
          [slot-prop n1] ((layered/p:layer :handler/add-numbers handler-result bank) n0)
          [reducer-props n2] ((dispatch/reduce-results
                               (dispatch/select-one-policy [:handler/add-numbers] default)
                               bank
                               out)
                              n1)
          n3 (-> n2
                 (nb/seed-cell handler-result 7)
                 (nb/run-propagators (into [slot-prop] reducer-props)))]
      (is (= 7 (net/network-cell-strongest n3 out)))))

  (testing "no usable slots falls back to default"
    (let [bank (ids/new-node-id)
          default (ids/new-node-id)
          out (ids/new-node-id)
          n0 (-> (installed-cells default out)
                 (dispatch/install-result-bank bank)
                 (nb/seed-cell default :default))
          [reducer-props n1] ((dispatch/reduce-results
                               (dispatch/select-one-policy [:handler/missing] default)
                               bank
                               out)
                              n0)
          n2 (-> n1
                 (nb/run-propagators reducer-props))]
      (is (= :default (net/network-cell-strongest n2 out)))))

  (testing "multiple usable slots produce contradiction"
    (let [bank (ids/new-node-id)
          default (ids/new-node-id)
          out (ids/new-node-id)
          left-result (ids/new-node-id)
          right-result (ids/new-node-id)
          n0 (-> (installed-cells default out left-result right-result)
                 (dispatch/install-result-bank bank)
                 (nb/seed-cell default :default))
          [left-slot-prop n1] ((layered/p:layer :handler/left left-result bank) n0)
          [right-slot-prop n2] ((layered/p:layer :handler/right right-result bank) n1)
          [reducer-props n3] ((dispatch/reduce-results
                               (dispatch/select-one-policy [:handler/left :handler/right] default)
                               bank
                               out)
                              n2)
          n4 (-> n3
                 (nb/seed-cell left-result :left)
                 (nb/seed-cell right-result :right)
                 (nb/run-propagators (into [left-slot-prop right-slot-prop] reducer-props)))]
      (is (= :bool4/contradiction (net/network-cell-strongest n4 out))))))
