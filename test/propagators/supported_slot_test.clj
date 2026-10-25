(ns propagators.supported-slot-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.message :as message]
            [propagators.datastructures.compound-object.merge :as owner]
            [propagators.datastructures.compound-object.network-slot :as slot]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.stdlib.premise-state :as state]
            [propagators.stdlib.premise-publication :as publication]))

(defn premise [source revision]
  #{{:source (ids/->NodeId
              (java.util.UUID/nameUUIDFromBytes (.getBytes (str source) "UTF-8")))
     :timestamp revision :premises-status :active}})

(defn supported [base source revision]
  (let [support (premise source revision)]
    (collection/content {:base base :support support :premise-state support})))

(defn payload [network id]
  (let [v (net/network-cell-strongest network id)]
    (if (datum/layer-present? v :base) (datum/layer-value v :base) v)))

(defn seed [network id content]
  (second (core/eval-cell id (message/message id content) network)))

(defn publish [network id content]
  (let [seeded (seed network id content)]
    (nb/run-propagators seeded (nb/neighbor-propagator-ids seeded id))))

(deftest publication-preserves-meaningful-pending-and-contradiction
  (doseq [base [value/nothing value/contradiction]
          membership? [false true]]
    (let [id (ids/new-node-id)
          support (premise :publication 1)
          result (cond-> {:base base :support support :premise-state support}
                   membership? (assoc :membership #{}))
          network (seed (nb/install-cells [id]) id (publication/content result))
          current (net/network-cell-strongest network id)]
      (is (= base (datum/layer-value current :base)))
      (is (= support (datum/support-of current)))
      (is (= support (state/states-of current))))))

(deftest supported-owner-pending-recovery-and-live-field-withdrawal
  (let [[source field target] (repeatedly 3 ids/new-node-id)
        object (owner/accessor-declaration :field field)
        prepared (-> (reduce nb/ensure-cell net/empty-net [source field target])
                     (seed source (supported value/nothing :selection 1))
                     (seed field (supported 10 :field 1)))
        [p installed] ((slot/p:network-slot :field target source) prepared)
        pending (nb/run-propagators installed [p])
        selected (publish pending source (supported object :selection 2))
        changed (publish selected field (supported 11 :field 2))
        withdrawn (publish changed source (supported value/nothing :selection 3))
        inactive (publish withdrawn field (supported 12 :field 3))
        recovered (publish inactive source (supported object :selection 4))]
    (is (value/nothing? (payload pending target)))
    (is (= (premise :selection 1)
           (datum/support-of (net/network-cell-strongest pending target))))
    (is (= 10 (payload selected target)))
    (is (= 11 (payload changed target)))
    (is (value/nothing? (payload withdrawn target)))
    (is (value/nothing? (payload inactive target)))
    (is (= 12 (payload recovered target)))
    (is (= (into (premise :selection 4) (premise :field 3))
           (datum/support-of (net/network-cell-strongest recovered target))))
    (is (= (into (premise :selection 4) (premise :field 3))
           (state/states-of (net/network-cell-strongest recovered target))))))

(deftest supported-owner-retains-all-aliases-and-meaningful-contradictions
  (let [[source first-field second-field target] (repeatedly 4 ids/new-node-id)
        object (-> (owner/accessor-declaration :field first-field)
                   (owner/register-accessor-parent :field second-field))
        prepared (-> (reduce nb/ensure-cell net/empty-net [source first-field second-field target])
                     (seed source (supported object :selection 1))
                     (seed first-field (supported 10 :first 1))
                     (seed second-field (supported 10 :second 1)))
        [p installed] ((slot/p:network-slot :field target source) prepared)
        selected (nb/run-propagators installed [p])
        conflicted (publish selected second-field (supported value/contradiction :second 2))
        recovered (publish conflicted second-field (supported 10 :second 3))]
    (is (= 10 (payload selected target)))
    (is (= (into (into (premise :selection 1) (premise :first 1)) (premise :second 1))
           (datum/support-of (net/network-cell-strongest selected target))))
    (is (value/contradiction? (payload conflicted target)))
    (is (= 10 (payload recovered target)))
    (is (= 2 (count (filter #(= [:propagators.datastructures.compound-object.supported-slot/watch :field]
                                (:name (val %))) (net/net-env recovered)))))))
