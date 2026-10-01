(ns propagators.experimental.compound-levels-demo-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.visualization-combinators.compound-levels :as demo]
            [graph.xr-runtime :as xr]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.closure-value :as closure]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.operators.call-graph :as calls]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.visualization.interaction :as interaction]
            [propagators.network :as net]))

(defn- binding-value [state symbol]
  (let [network (:program/net state)
        id (env/resolve-binding-id network (:program/env state) symbol)]
    (data/payload (net/network-cell-strongest network id))))

(deftest compound-levels-publish-three-directed-graphs
  (let [state @(loader/load-session-from-file demo/example demo/options)
        views (mapv xr/view->json (interaction/published-views state))
        children (take 3 views)
        result (env/resolve-binding-id (:program/net state) (:program/env state) 'result)]
    (is (= 10201 (data/payload (net/network-cell-strongest (:program/net state) result))))
    (is (= 6 (count views)))
    (is (= ["graph" "graph" "graph"] (mapv :type children)))
    (is (= [5 4 4] (mapv #(count (get-in % [:graph :edges])) children)))
    (is (= [6 5 5] (mapv #(count (get-in % [:graph :nodes])) children)))
    (let [graph (:graph (first children))
          labels (into {} (map (juxt :id :label) (:nodes graph)))]
      (is (= #{["2" "source"] ["source" "stage"] ["stage" "prepared"]
               ["prepared" "pipeline"] ["pipeline" "result"]}
             (set (map (fn [{:keys [from to]}] [(labels from) (labels to)])
                       (:edges graph))))))))

(deftest child-cells-compose-through-list-and-value-projections
  (let [state @(loader/load-session-from-file demo/example demo/options)
        network (:program/net state)
        body (binding-value state 'stage-body)
        expected-cells (set (keep (fn [[id kind]] (when (= :cell kind) id))
                                  (:node-kinds body)))
        refs (data/resolve-collection network (binding-value state 'child-list))
        values (data/resolve-collection network (binding-value state 'child-values))]
    (is (= :list (:kind refs) (:kind values)))
    (is (= 3 (count expected-cells)))
    (is (= expected-cells (set (map :identity (:items refs)))))
    (is (= (mapv :identity (:items refs)) (mapv :identity (:items values))))
    (is (= #{2 3 9} (set (map :payload (:items values)))))
    (doseq [[reference projected] (map vector (:items refs) (:items values))]
      (is (data/reference? (:payload reference)))
      (is (contains? (:sources projected) (:payload reference)))
      (is (= (:payload projected)
             (data/payload (data/read-strongest network (:payload reference))))))
    (is (empty? (:edges refs)))))

(deftest compiled-calls-come-from-retained-closure-syntax
  (let [state @(loader/load-session-from-file demo/example demo/options)
        declaration (application/callable-declaration (binding-value state 'stage))
        graph (binding-value state 'compiled-calls)
        potential (filter #(= :potential (:call/status %)) (vals (:values graph)))
        realized (filter #(= :realized (:call/status %)) (vals (:values graph)))
        sites (calls/call-sites (closure/closure-body declaration))]
    (is (closure/closure-info? declaration))
    (is (ast/ast-node? (closure/closure-body declaration)))
    (is (= (set (map (juxt :path :operator-label) sites))
           (set (map (juxt :call/path :call/operator) potential))))
    (is (= #{"shift" "square" "->"} (set (map :call/operator potential))))
    (is (seq realized))
    (is (= 3 (count (filter #(= "call shift" %) (vals (:nodes graph))))))
    (is (= 3 (count (filter #(= "call square" %) (vals (:nodes graph))))))))
