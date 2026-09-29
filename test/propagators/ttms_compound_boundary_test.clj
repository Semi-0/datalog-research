(ns propagators.ttms-compound-boundary-test
  "Migration gate: whole supported compound values versus supported slot values."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.core :as core]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-primitives :as primitives]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- slot-result [supported?]
  (let [[owner output] (repeatedly 2 ids/new-node-id)
        base (obj/compound-object {:x 10})
        support #{{:source owner :timestamp 1 :premises-status :active}}
        content (if supported? (collection/content {:base base :support support}) base)
        initial (nb/install-cells [owner output])
        [_ seeded] (core/eval-cell owner (message/message owner content) initial)
        [installed tasks] (if supported?
                            (let [[n tasks _] ((operator/operator-install
                                               (primitives/slot-operator :x))
                                              seeded [owner] output)]
                              [n tasks])
                            (let [[id n] ((obj/p:network-slot :x output owner) seeded)]
                              [n [id]]))
        after (nb/run-propagators installed tasks)]
    {:result (net/network-cell-strongest after output) :support support}))

(deftest whole-supported-compound-slot-transport
  (is (= 10 (:result (slot-result false))))
  (let [{:keys [result support]} (slot-result true)]
    (is (= 10 (datum/layer-value result :base)))
    (is (= support (datum/support-of result)))
    (is (not (value/unusable? result)))))

(defn- premise [source epoch status]
  {:source source :timestamp epoch :premises-status status})

(defn- inject [network owner base premises]
  (let [content (collection/content {:base base :support premises})
        [tasks patched] (core/eval-cell owner (message/message owner content) network)]
    (nb/run-propagators patched tasks)))

(defn- install-slot [network owner slot output]
  (let [[installed tasks _] ((operator/operator-install (primitives/slot-operator slot))
                            network [owner] output)]
    {:network (nb/run-propagators installed tasks) :tasks tasks}))

(defn- check-result [network output expected premises]
  (let [result (net/network-cell-strongest network output)]
    (is (= expected (datum/layer-value result :base)))
    (is (= premises (datum/support-of result)))))

(deftest slot-lifecycle-and-source-isolation
  (let [[owner output] (repeatedly 2 ids/new-node-id)
        p #(hash-set (premise owner %1 %2))
        seed (inject (nb/install-cells [owner output]) owner
                     (obj/compound-object {:x 10}) (p 1 :active))
        {:keys [network tasks]} (install-slot seed owner :x output)
        updated (inject network owner (obj/compound-object {:x 20}) (p 2 :active))
        withdrawn (inject updated owner value/nothing (p 3 :retracted))
        restored (inject withdrawn owner (obj/compound-object {:x 7}) (p 4 :active))]
    (doseq [[n base epoch status] [[network 10 1 :active] [updated 20 2 :active]
                                   [withdrawn value/nothing 3 :retracted]
                                   [restored 7 4 :active]]]
      (check-result n output base (p epoch status)))
    (is (= (net/network-cell-content seed owner)
           (net/network-cell-content network owner)) "Access does not declare into the source")
    (is (= (set (keys (net/net-env network))) (set (keys (net/net-env restored)))))
    (is (= (net/net-env restored) (net/net-env (nb/run-propagators restored tasks))))
    (is (= (net/net-env restored)
           (net/net-env (inject restored owner (obj/compound-object {:x 20}) (p 2 :active))))
        "A delayed old environment cannot restore old values")))

(deftest missing-slot-arrives-later
  (let [[owner output] (repeatedly 2 ids/new-node-id)
        p #(hash-set (premise owner % :active))
        seeded (inject (nb/install-cells [owner output]) owner
                       (obj/compound-object {}) (p 1))
        {:keys [network]} (install-slot seeded owner :x output)]
    (check-result network output value/nothing (p 1))
    (check-result (inject network owner (obj/compound-object {:x false}) (p 2))
                  output false (p 2))))

(defn- environment-value [x]
  (obj/compound-object {:scope (obj/compound-object {:x x})}))

(deftest compiled-nested-environment-and-dependent-computation
  (let [owner (ids/new-node-id)
        p #(hash-set (premise owner %1 %2))
        seed (inject (nb/install-cells [owner]) owner (environment-value 10) (p 1 :active))
        compiled (compiler/compile-expr-with-bindings
                  (parser/parse-string "(+ (read-x (read-scope environment)) 1)")
                  (into (vec (basis/default-bindings))
                        (concat (extension/extension-bindings primitives/session-extension)
                                [['read-x (primitives/slot-operator :x)]
                                 ['read-scope (primitives/slot-operator :scope)]
                                 ['environment (env/cell-binding owner)]]))
                  {:net seed})
        network (nb/run-propagators (:net compiled) (:props compiled))
        updated (inject network owner (environment-value 20) (p 2 :active))
        withdrawn (inject updated owner value/nothing (p 3 :retracted))
        restored (inject withdrawn owner (environment-value 7) (p 4 :active))]
    (doseq [[n base epoch status] [[network 11 1 :active] [updated 21 2 :active]
                                   [withdrawn value/nothing 3 :retracted] [restored 8 4 :active]]]
      (check-result n (:cell compiled) base (p epoch status)))
    (is (= (set (keys (net/net-env network))) (set (keys (net/net-env restored)))))
    (is (= (net/net-env restored)
           (net/net-env (nb/run-propagators restored (:props compiled)))))))

(deftest multiple-owner-premises-survive-access
  (let [[owner b output] (repeatedly 3 ids/new-node-id)
        a1 (premise owner 1 :active)
        b1 (premise b 1 :active)
        a2 (premise owner 2 :retracted)
        a3 (premise owner 3 :active)
        seed (inject (nb/install-cells [owner b output]) owner
                     (obj/compound-object {:x 10}) #{a1 b1})
        {:keys [network]} (install-slot seed owner :x output)
        withdrawn (inject network owner value/nothing #{a2 b1})
        restored (inject withdrawn owner (obj/compound-object {:x 7}) #{a3 b1})]
    (check-result network output 10 #{a1 b1})
    (check-result withdrawn output value/nothing #{a2 b1})
    (check-result restored output 7 #{a3 b1})))

(deftest slot-local-support-is-not-lost
  (let [[owner local-source output] (repeatedly 3 ids/new-node-id)
        parent (premise owner 1 :active)
        child (premise local-source 1 :active)
        base (obj/compound-object {:x (obj/compound-object {:base 10 :support #{child}})})
        seed (inject (nb/install-cells [owner local-source output]) owner base #{parent})
        {:keys [network]} (install-slot seed owner :x output)
        result (net/network-cell-strongest network output)]
    (testing "The outer dependency and nested datum remain inspectable"
      (is (= #{parent} (datum/support-of result)))
      (let [base (datum/layer-value result :base)
            slot (if (evidence/evidence-set? base) (evidence/strongest base) base)]
        (is (= 10 (datum/layer-value slot :base)))
        (is (= #{child} (datum/support-of slot)))))))

(defn- check-domain-slots [domain]
  (let [[owner output] (repeatedly 2 ids/new-node-id)
        premises #{(premise owner 1 :active)}
        seed (inject (nb/install-cells [owner output]) owner
                     (obj/compound-object domain) premises)
        {:keys [network]} (install-slot seed owner :x output)]
    (check-result network output 10 premises)))

(deftest domain-base-slot-is-not-an-outer-layer
  (check-domain-slots {:x 10 :base value/nothing}))

(deftest domain-support-slot-is-not-an-outer-layer
  (check-domain-slots {:x 10 :support :domain-data}))

(deftest domain-base-and-support-slots-are-not-outer-layers
  (check-domain-slots {:x 10 :base value/nothing :support :domain-data}))

(deftest slot-retraction-is-visible-to-downstream-readiness
  (let [[owner child-source output] (repeatedly 3 ids/new-node-id)
        parent (premise owner 1 :active)
        child (premise child-source 2 :retracted)
        base (obj/compound-object {:x (obj/compound-object {:base 10 :support #{child}})})
        seed (inject (nb/install-cells [owner child-source output]) owner base #{parent})
        {:keys [network]} (install-slot seed owner :x output)
        result (net/network-cell-strongest network output)]
    (is (value/unusable? result)
        "Preserved nested retraction must prevent use of the selected slot")))

(deftest independent-environment-evidence-remains-conjunctive
  (let [[owner other output] (repeatedly 3 ids/new-node-id)
        a1 (premise owner 1 :active)
        b1 (premise other 1 :active)
        a2 (premise owner 2 :retracted)
        a3 (premise owner 3 :active)
        base (obj/compound-object {:x 10})
        seed (-> (nb/install-cells [owner other output])
                 (inject owner base #{a1})
                 (inject owner base #{b1}))
        {:keys [network]} (install-slot seed owner :x output)
        withdrawn (inject network owner value/nothing #{a2})
        restored (inject withdrawn owner base #{a3 b1})]
    (check-result network output 10 #{a1 b1})
    (is (value/unusable? (net/network-cell-strongest withdrawn output))
        "Independent B does not override the established conjunctive rule")
    (is (= #{a2 b1} (datum/support-of (net/network-cell-strongest withdrawn output))))
    (check-result restored output 10 #{a3 b1})))
