(ns propagators.compiler-2.runtime.relationship-observer-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.model.env :as cenv]
            [propagators.experimental.visualization.data :as data]
            [propagators.semantic-trace.modifiers :as modifiers]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.operators.relationship-observer :as operators]
            [propagators.compiler-2.runtime.extensions.relationship-xr :as relationship-xr]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.network :as net]
            [propagators.relationship :as relationship]
            [propagators.relationship-observer :as relationship-observer]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.runner :as runner]
            [propagators.semantic-trace :as semantic-trace]))

(defn- load-observed [source options]
  (let [steps (atom 0)]
    (binding [runner/*advance-transform*
              (fn [advance]
                (fn [state continuations]
                  (when (> (swap! steps inc) 5000)
                    (throw (ex-info "Observer did not reach quiescence" {})))
                  (advance state continuations)))]
      (loader/load-session-from-source source options))))

(deftest relationship-extension-compiles-a-composed-root-observer
  (let [session
        (load-observed
          "(define a (cell)) (define b (cell)) (define graph (cell))
          (<-> 1 a)
          (-> (+ a 1) b)
          (relationship:roots a graph)
          (xr:io graph)"
         {:client-id "relationship-test"
          :extensions [(relationship-xr/extension)]})
        network (:program/net @session)
        graph-id (cenv/resolve-binding-id network
                                           (:program/env @session)
                                           'graph)
        a-id (cenv/resolve-binding-id network (:program/env @session) 'a)
        b-id (cenv/resolve-binding-id network (:program/env @session) 'b)
        traced (data/payload (net/network-cell-strongest network graph-id))
        traced-ids (set (map second (keys (:nodes traced))))
        labels (set (vals (:nodes traced)))]
    (is (semantic-trace/semantic-trace-graph? traced))
    (is (contains? traced-ids a-id))
    (is (contains? traced-ids b-id))
    (is (not (contains? traced-ids graph-id)))
    (is (every? #(empty? (relationship/parents
                          (net/net-relationship network) %))
                (keys (:nodes traced))))
    (is (not-any? #(= relationship-observer/observer-name %)
                  (vals (:nodes traced))))
    (is (contains? labels '+))
    (is (contains? labels '->))
    (is (seq (:edges traced)))
    (is (seq (get-in @session [:xr :effects])))
    (is (= "/api/relationships"
           relationship-xr/relationship-endpoint))))

(deftest dataflow-is-an-expression-and-first-class-callable
  (doseq [expression ["(relationship:dataflow raw)"
                      "((network [graph] (relationship:dataflow graph)) raw)"
                      "((network [project graph] (project graph)) relationship:dataflow raw)"
                      "(relationship:dataflow raw semantic)"]]
    (let [session (load-observed
                   (str "(def-cells a b raw semantic) (-> 3 a) (-> (+ a 4) b) "
                        "(relationship:roots a raw) (define result " expression ")")
                   {:extensions [(relationship-xr/extension)]})
          state @session network (:program/net state)
          id (cenv/resolve-binding-id network (:program/env state) 'result)
          _ (when-not id (throw (ex-info "Observer expression failed" {:expression expression :errors (:runtime/errors state)})))
          graph (data/payload (net/network-cell-strongest network id))]
      (is (semantic-trace/semantic-trace-graph? graph) expression)
      (is (contains? (set (vals (:nodes graph))) '+) expression)
      (is (seq (:edges graph)) expression))))

(deftest dataflow-rejects-unsupported-arities
  (doseq [expression ["(relationship:dataflow)" "(relationship:dataflow a b c)"]]
    (let [session (load-observed expression {:extensions [(relationship-xr/extension)]})]
      (is (some? (get-in (loader/load-source! session expression) [:load-result :error]))))))

(deftest observers-use-the-normal-callable-installer-protocol
  (doseq [declaration [(operators/roots-operator) (operators/semantic-operator)
                        (operators/roots-semantic-operator)]]
    (is (fn? (operator/operator-install declaration)))
    (is (nil? (operator/operator-compiler-operands declaration))))
  (doseq [name ["relationship:roots" "relationship:semantic" "relationship:roots-semantic"]
          expression [(str "(" name " a)")
                      (str "((network [seed] (" name " seed)) a)")
                      (str "((network [observe seed] (observe seed)) " name " a)")
                      (str "(" name " a output)")
                      (str "(" name " a b output)")]]
    (let [session (load-observed
                   (str "(def-cells a b output) (-> 3 a) (-> (+ a 4) b) "
                        "(define result " expression ")")
                   {:extensions [(relationship-xr/extension)]})
          state @session network (:program/net state)
          id (cenv/resolve-binding-id network (:program/env state) 'result)
          _ (when-not id (throw (ex-info "Observer expression failed" {:expression expression :errors (:runtime/errors state)})))
          graph (data/payload (net/network-cell-strongest network id))]
      (is (semantic-trace/semantic-trace-graph? graph) expression)
      (is (seq (:edges graph)) expression))))

(deftest direct-semantic-is-the-shared-roots-dataflow-composition
  (let [session (load-observed
                 "(def-cells a b) (-> 3 a) (-> (+ a 4) b)
                  (define raw (relationship:roots a))
                  (define composed (relationship:dataflow raw))
                  (define direct (relationship:roots-semantic a))"
                 {:extensions [(relationship-xr/extension)]})
        state @session network (:program/net state)
        read-binding (fn [name] (data/payload (net/network-cell-strongest network
                                 (cenv/resolve-binding-id network (:program/env state) name))))
        composed (read-binding 'composed) direct (read-binding 'direct)]
    (is (= :leapfrog (:dataflow/projection composed)))
    (let [expected (modifiers/caller-view (dataflow/semantic-network-graph network (read-binding 'raw)))]
      (is (= (select-keys expected [:nodes :edges :node-kinds])
             (select-keys direct [:nodes :edges :node-kinds]))))
    (is (= :leapfrog (:dataflow/projection direct)))
    (is (= :root (:dataflow/scope direct)))
    (is (contains? (set (vals (:nodes direct))) '+))
    (is (not-any? #{'relationship:roots 'relationship:semantic 'relationship:roots-semantic 'relationship:dataflow}
                  (vals (:nodes direct))))))

(deftest observers-reject-missing-seeds
  (doseq [name ["relationship:roots" "relationship:semantic" "relationship:roots-semantic"]]
    (let [session (load-observed "(def-cells a)" {:extensions [(relationship-xr/extension)]})]
      (is (some? (get-in (loader/load-source! session (str "(" name ")")) [:load-result :error]))))))
