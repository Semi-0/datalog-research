(ns propagators.sync-contradiction-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.cells.error-layer :as errors]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.model.env :as env]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.stdlib.boundary :as boundary]
            [propagators.stdlib.observation :as observation]
            [propagators.stdlib.prop :as standard]))

(defn- publish [network target update]
  (let [[tasks patched] (core/eval-cell target (message/message target update) network)]
    (nb/run-propagators patched tasks)))

(defn- compiled-link [code source target]
  (let [compiled (compiler/compile-expr-with-bindings
                  (parser/parse-string code)
                  (into (vec (basis/default-bindings))
                        [['input (env/cell-binding source)]
                         ['output (env/cell-binding target)]])
                  {:net (nb/install-cells [source target])})]
    {:network (nb/run-propagators (:net compiled) (:props compiled))
     :tasks (:props compiled)}))

(defn- observed [source epoch base]
  (let [support #{{:source source :timestamp epoch :premises-status :active}}]
    (collection/content {:base base :support support :premise-state support})))

(deftest compiled-sync-forwards-contradiction-with-provenance
  (doseq [code ["(-> input output)" "(<-> input output)"]
          contradiction [value/contradiction
                         (value/contradiction-with-provenance #{:source-error})
                         (errors/attach value/contradiction #{{:message "source error"}})]]
    (testing code
      (let [[source target] (repeatedly 2 ids/new-node-id)
            {:keys [network tasks]} (compiled-link code source target)
            after (publish network source contradiction)
            result (net/network-cell-strongest after target)]
        (is (value/contradiction? result))
        (is (= (errors/errors-of contradiction) (errors/errors-of result)))
        (is (= (value/contradiction-provenance contradiction)
               (value/contradiction-provenance result)))
        (is (= after (nb/run-propagators after tasks)))))))

(deftest low-level-identities-and-boundaries-preserve-contradiction
  (doseq [installer [(fn [a b] (standard/id a b))
                     (fn [a b] (observation/identity ::identity a b))
                     (fn [a b]
                       (fn [network]
                         (let [after (boundary/bi-sync nil [a b] [a b] network)]
                           [(vec (nb/neighbor-propagator-ids after a)) after])))
                     (fn [a b] #(boundary/fast-bi-sync % a b))]]
    (let [[source target] (repeatedly 2 ids/new-node-id)
          [tasks network] ((installer source target) (nb/install-cells [source target]))
          problem (value/contradiction-with-provenance #{:original})
          after (publish network source problem)]
      (is (= problem (net/network-cell-strongest after target)))
      (is (= after (nb/run-propagators after (flatten [tasks])))))))

(deftest bidirectional-feedback-unions-provenance-without-new-topology
  (let [[source target] (repeatedly 2 ids/new-node-id)
        {:keys [network tasks]} (compiled-link "(<-> input output)" source target)
        after (-> network
                  (publish source (value/contradiction-with-provenance #{:left}))
                  (publish target (value/contradiction-with-provenance #{:right})))]
    (doseq [id [source target]]
      (is (= #{:left :right}
             (value/contradiction-provenance (net/network-cell-strongest after id)))))
    (is (= (set (keys (net/net-env network))) (set (keys (net/net-env after)))))
    (is (= after (nb/run-propagators after tasks)))))

(deftest supported-contradictions-remain-retractable-through-sync
  (doseq [code ["(-> input output)" "(<-> input output)"]]
    (let [[source target] (repeatedly 2 ids/new-node-id)
          {:keys [network tasks]} (compiled-link code source target)
          problem (errors/attach
                   (value/contradiction-with-provenance #{:measurement})
                   #{{:message "invalid measurement"}})
          bad (publish network source (observed source 1 problem))
          result (net/network-cell-strongest bad target)
          repaired (publish bad source (observed source 2 42))]
      (is (= problem (datum/layer-value result :base)))
      (is (= #{{:source source :timestamp 1 :premises-status :active}}
             (datum/support-of result)))
      (is (= 42 (datum/layer-value (net/network-cell-strongest repaired target) :base)))
      (is (= repaired (publish repaired source (observed source 1 problem))))
      (is (= repaired (nb/run-propagators repaired tasks))))))

(deftest slot-peers-forward-plain-contradiction
  (let [[source target owner] (repeatedly 3 ids/new-node-id)
        [p1 n1] ((obj/p:slot :x source owner) (nb/install-cells [source target owner]))
        [p2 n2] ((obj/p:slot :x target owner) n1)
        network (nb/run-propagators n2 [p1 p2])
        problem (value/contradiction-with-provenance #{:slot-source})
        after (publish network source problem)]
    (is (= problem (net/network-cell-strongest after target)))
    (is (= after (nb/run-propagators after [p1 p2])))))

(deftest compound-source-slot-forwards-plain-contradiction
  (let [[owner target] (repeatedly 2 ids/new-node-id)
        problem (value/contradiction-with-provenance #{:compound-slot})
        initial (nb/seed-cell (nb/install-cells [owner target]) owner
                              (obj/compound-object {:x problem}))
        [id installed] ((obj/p:slot :x target owner) initial)
        after (nb/run-propagators installed [id])]
    (is (= problem (net/network-cell-strongest after target)))
    (is (= after (nb/run-propagators after [id])))))
