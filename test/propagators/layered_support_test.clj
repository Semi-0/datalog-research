(ns propagators.layered-support-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.provenance-arithmetic :as arithmetic]
            [propagators.stdlib.support :as support]))

(def source-ids (zipmap [:A :B] (repeatedly 2 ids/new-node-id)))

(defn- premise [source timestamp state]
  {:source (get source-ids source source)
   :timestamp timestamp :premises-status state})

(defn- supported [base & supports]
  (obj/compound-object {:base base :support (set supports)}))

(defn- install-supported-add []
  (let [{network :net procedure :proc} (arithmetic/+ net/empty-net)
        closure (ids/new-node-id)
        prepared (nb/install-cell network closure support/procedure support/procedure)
        installed (layered/install-layered-procedure! prepared procedure :support closure)]
    [(:net installed) procedure]))

(defn- run-add [network procedure left right]
  (let [[a b out] (repeatedly 3 ids/new-node-id)
        prepared (-> network
                     (nb/install-cell a left left)
                     (nb/install-cell b right right)
                     (nb/install-cell out))
        [task installed] ((layered/p:apply-layered procedure [a b] out) prepared)
        after (nb/run-propagators installed [task])]
    {:network after :value (net/network-cell-strongest after out)}))

(deftest support-is-composed-through-an-ordinary-procedure-layer
  (let [[network procedure] (install-supported-add)
        a (premise :A 1 :active)
        b (premise :B 7 :active)
        output (:value (run-add network procedure (supported 10 a) (supported 20 b)))]
    (is (= 30 (datum/layer-value output :base)))
    (is (= #{a b} (datum/support-of output)))
    (is (not (value/unusable? output)))))

(deftest incompatible-support-does-not-prevent-base-computation
  (let [[network procedure] (install-supported-add)
        a1 (premise :A 1 :active)
        a2 (premise :A 2 :active)
        output (:value (run-add network procedure (supported 10 a1) (supported 20 a2)))]
    (is (= 30 (datum/layer-value output :base)))
    (is (= #{a1 a2} (datum/support-of output)))
    (is (value/unusable? output))))

(deftest retraction-reaches-another-layered-computation
  (let [[network procedure] (install-supported-add)
        retracted (premise :A 2 :retracted)
        first-result (run-add network procedure (supported 10 retracted) 20)
        second-result (run-add (:network first-result) procedure (:value first-result) 5)]
    (is (= 30 (datum/layer-value (:value first-result) :base)))
    (is (= 35 (datum/layer-value (:value second-result) :base)))
    (is (= #{retracted} (datum/support-of (:value second-result))))
    (is (value/unusable? (:value second-result)))))

(deftest nothing-base-still-transports-support
  (let [[network procedure] (install-supported-add)
        retracted (premise :A 2 :retracted)
        output (:value (run-add network procedure (supported value/nothing retracted) 20))]
    (is (= #{retracted} (datum/support-of output)))
    (is (value/nothing? (datum/layer-value output :base)))
    (is (value/unusable? output))))

(deftest wired-chain-carries-retraction-but-concrete-consumer-does-not-run
  (let [[network procedure] (install-supported-add)
        [a b c intermediate out consumed] (repeatedly 6 ids/new-node-id)
        retracted (premise a 2 :retracted)
        argument (supported 10 retracted)
        prepared (-> network
                     (nb/install-cell a argument argument)
                     (nb/install-cell b 20 20)
                     (nb/install-cell c 5 5)
                     (nb/install-cell intermediate)
                     (nb/install-cell out)
                     (nb/install-cell consumed))
        [first-task n1] ((layered/p:apply-layered procedure [a b] intermediate) prepared)
        [second-task n2] ((layered/p:apply-layered procedure [intermediate c] out) n1)
        [consumer n3] (((prop/concrete-primitive-propagator (constantly :ran)) out consumed) n2)
        after (nb/run-propagators n3 [first-task second-task consumer])
        result (net/network-cell-strongest after out)]
    (is (= 35 (datum/layer-value result :base)))
    (is (= #{retracted} (datum/support-of result)))
    (is (value/unusable? result))
    (is (value/nothing? (net/network-cell-strongest after consumed)))))

(deftest contradictory-base-does-not-bypass-the-support-procedure
  (let [[network procedure] (install-supported-add)
        active (premise :A 1 :active)
        output (:value (run-add network procedure
                                (supported value/contradiction active) 20))]
    (is (= #{active} (datum/support-of output)))
    (is (value/contradiction? (datum/layer-value output :base)))
    (is (value/unusable? output))))
