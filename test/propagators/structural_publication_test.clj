(ns propagators.structural-publication-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.structural-field :as field]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- publish [network target content]
  (let [[tasks n] (core/eval-cells [(message/message target content)] network)]
    (nb/run-propagators n tasks)))

(defn- source-update [network source base status]
  (publish network source
           (collection/content
            (publication/next-source-datum source
              (net/network-cell-strongest network source) base status))))

(deftest stamped-snapshot-reaches-real-slot-and-recovers
  (let [[input source owner left right] (repeatedly 5 ids/new-node-id)
        sample (fn [n] {:x (datum/layer-value (net/network-cell-strongest n input) :base)})
        [a n1] ((obj/p:network-slot :x left owner) (nb/install-cells [input source owner left right]))
        [b n2] ((obj/p:network-slot :x right owner) n1)
        [f n3] ((field/p:structural-field source :x left) n2)
        [watch n4] ((publication/p:observe (ids/new-node-id) input source sample) n3)
        prepared (nb/run-propagators n4 [a b])
        ready (source-update prepared input 10 :active)
        updated (source-update ready input 20 :active)
        unchanged (nb/run-propagators updated [watch f a b])
        withdraw (prop/compose-activation
                  (fn [_ _ _] [(message/message source value/nothing)])
                  (publication/stamp-source source :retracted)
                  (message/lift-message collection/content))
        [tasks n5] (core/eval-cells (withdraw [] [] updated) updated)
        withdrawn (nb/run-propagators n5 tasks)
        recovered (nb/run-propagators withdrawn [watch])]
    (doseq [[n epoch base unusable?] [[ready 1 10 false] [updated 2 20 false]
                                     [withdrawn 3 value/nothing true] [recovered 4 20 false]]
            target [left right]]
      (let [v (net/network-cell-strongest n target)]
        (is (= base (datum/layer-value v :base)))
        (is (= #{source} (set (map :source (datum/support-of v)))))
        (is (= #{epoch} (set (map :timestamp (datum/support-of v)))))
        (is (= unusable? (value/unusable? v)))))
    (is (= updated unchanged))
    (is (= (net/net-graph prepared) (net/net-graph recovered)))
    (is (= 1 (count (:support/observations (net/network-cell-content recovered source)))))
    (is (= (sample recovered)
           (datum/layer-value (net/network-cell-strongest recovered source) :base)))
    (let [independent (publish recovered right
                       (collection/content {:base 99 :support #{{:source right :timestamp 1 :premises-status :active}}}))]
      (is (value/contradiction? (datum/layer-value (net/network-cell-strongest independent left) :base)))
      (is (= (net/network-cell-content recovered source)
             (net/network-cell-content independent source))))))
