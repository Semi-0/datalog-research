(ns propagators.compiler-2-application-runtime-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.application-output :as output]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

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
      (is (not (scope-source/scope-value?
                (net/network-cell-strongest result @result-cell)))))))
