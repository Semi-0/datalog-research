(ns propagators.compiler-2-application-runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.application-layers :as layers]
            [propagators.compiler-2.runtime.application-output :as output]
            [propagators.compiler-2.runtime.topology-effects :as topology]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.message :as msg :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- scoped [source chain v dependencies]
  (scope-source/scope-value source nil chain v dependencies))

(deftest compatible-scopes-combine-only-explicit-provenance
  (let [root (scoped :root [:root :child] 1 #{:root})
        child (scoped :child [:root :child] 2 #{:child})
        unrelated (scoped :other [:other] 3 #{:other})
        unknown-source (scoped :unknown [:root :child] 4 #{:unknown})
        scope (layers/application-scope [root 99 child])]
    (is (nil? (layers/application-scope [])))
    (is (nil? (layers/application-scope [1 2])))
    (is (nil? (layers/application-scope [root unrelated])))
    (is (= child (:candidate scope)))
    (is (= #{:root :child} (:dependencies scope)))
    (is (= root (:candidate (layers/application-scope [unknown-source root]))))
    (is (= scope (application/application-scope [root 99 child])))
    (is (= 2 (layers/unwrap-operator child)))
    (is (= false (layers/unwrap-operator false)))))

(deftest result-provenance-preserves-ordinary-values-and-other-messages
  (let [scope {:dependencies #{:input}}
        answer (scoped :root [:root] 12 #{:result})
        messages [(message :out answer) (message :other answer)]
        refined (layers/scope-activation-result scope :out messages)
        effect {:effect :retained}
        result (layers/scope-activation-result
                scope :out {:effects [effect] :messages messages})]
    (is (= #{:input :result}
           (scope-source/dependencies (msg/message-value (first refined)))))
    (is (= (second messages) (second refined)))
    (is (= [effect] (:effects result)))
    (is (= refined (:messages result)))
    (is (= messages (layers/scope-activation-result nil :out messages)))
    (is (= [(message :out false)]
           (layers/scope-activation-result scope :out [(message :out false)])))
    (is (= {:messages []}
           (layers/scope-activation-result scope :out {})))
    (is (= :unsupported (layers/scope-activation-result scope :out :unsupported)))))

(deftest base-readers-select-existing-pending-and-raw-sources
  (let [spec (first (layers/source-specs :application :source [:arg]))
        specs (layers/source-specs :application :source [:arg])]
    (is (= [:operator [:arg 0]] (mapv :role specs)))
    (is (= [:source :arg] (mapv :source-id specs)))
    (with-redefs [h/strongest-or-nothing (fn [_ _] :value)
                  layered/layer-addressable? (fn [_ _] false)
                  layered/layer-parent-id (fn [_ _ _] nil)]
      (is (= :source (layers/evaluation-id net/empty-net spec)))
      (is (= [] (layers/pending-base-readers net/empty-net :application specs))))
    (with-redefs [h/strongest-or-nothing (fn [_ _] :value)
                  layered/layer-addressable? (fn [_ _] true)
                  layered/layer-parent-id (fn [_ _ _] nil)
                  topology/declared? (fn [_ _] false)]
      (is (= (:base-id spec) (layers/evaluation-id net/empty-net spec)))
      (is (= specs (layers/pending-base-readers net/empty-net :application specs)))
      (with-redefs [topology/declared? (fn [_ _] true)]
        (is (= [] (layers/pending-base-readers net/empty-net :application specs)))))
    (with-redefs [layered/layer-parent-id (fn [_ _ _] :existing)]
      (is (= :existing (layers/evaluation-id net/empty-net spec))))
    (is (= {:effects [] :messages []}
           (layers/declare-pending-base-readers net/empty-net :application [])))))

(deftest explicit-result-addresses-stay-at-the-layer-boundary
  (let [address (ids/new-node-id)
        network (nb/install-cell net/empty-net address)
        answer (scoped :root [:root] 12 #{})]
    (with-redefs [h/strongest-or-nothing (fn [_ _] answer)
                  scope-source/binding-address (fn [_] address)
                  layered/layer-parent-id (fn [_ _ _] :base)]
      (is (= address (layers/result-value-id network :result)))
      (is (= :base (layers/result-value-id net/empty-net :result))))
    (with-redefs [h/strongest-or-nothing (fn [_ _] answer)
                  scope-source/binding-address (fn [_] :invalid)
                  layered/layer-parent-id (fn [_ _ _] nil)]
      (is (= :result (layers/result-value-id network :result))))
    (with-redefs [h/strongest-or-nothing (fn [_ _] 12)
                  layered/layer-parent-id (fn [_ _ _] nil)]
      (is (= :result (layers/result-value-id network :result))))))

(deftest output-export-preserves-scalars-and-suppresses-unchanged-values
  (doseq [v [false 0 12]]
    (let [id (ids/new-node-id)
          before (nb/install-cell net/empty-net id)
          after (nb/seed-cell before id v)]
      (is (= v (output/externalized-cell-value after id)))
      (is (= [(message id v)]
             (vec (output/externalized-output-messages after before [id]))))
      (is (empty? (output/externalized-output-messages after after [id])))))
  (is (= value/nothing (output/externalized-cell-value net/empty-net :missing))))

(deftest sub-environment-publishes-the-raw-compiler-result-cell
  (doseq [v [false 41]]
    (let [parent-id (ids/new-node-id)
          expr-id (ids/new-node-id)
          child-id (ids/new-node-id)
          out-id (ids/new-node-id)
          x-id (ids/new-node-id)
          result-cell (atom nil)
          compile* (fn [state expr]
                     (let [[compiled binding] (compiler/default-compiler state expr)]
                       (reset! result-cell (env/binding-id binding))
                       [compiled binding]))
          network (-> net/empty-net
                      (h/seed-cell x-id v)
                      (h/seed-cell parent-id
                                   (env/bind (h/default-env) 'x
                                             (env/cell-binding x-id) 0))
                      (h/seed-cell expr-id (ast/sym 'x)))
          [prop-id installed]
          ((application/p:execute-sub-env-with compile* parent-id expr-id []
                                                child-id out-id) network)
          result (nb/run-propagators installed [prop-id])]
      (is (= v (net/network-cell-strongest result out-id)))
      (is (= v (net/network-cell-strongest result @result-cell)))
      (is (= @result-cell (layers/result-value-id result @result-cell)))
      (is (not (scope-source/scope-value?
                (net/network-cell-strongest result @result-cell)))))))
