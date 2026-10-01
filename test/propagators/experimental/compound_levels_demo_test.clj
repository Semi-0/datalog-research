(ns propagators.experimental.compound-levels-demo-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.visualization-combinators.compound-levels :as demo]
            [graph.xr-runtime :as xr]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.visualization.interaction :as interaction]
            [propagators.network :as net]))

(deftest compound-levels-publish-three-directed-graphs
  (let [state @(loader/load-session-from-file demo/example demo/options)
        views (mapv xr/view->json (interaction/published-views state))
        children views
        result (env/resolve-binding-id (:program/net state) (:program/env state) 'result)]
    (is (= 10201 (data/payload (net/network-cell-strongest (:program/net state) result))))
    (is (= 3 (count views)))
    (is (= ["graph" "graph" "graph"] (mapv :type children)))
    (is (= [5 4 4] (mapv #(count (get-in % [:graph :edges])) children)))
    (is (= [6 5 5] (mapv #(count (get-in % [:graph :nodes])) children)))
    (let [graph (:graph (first children))
          labels (into {} (map (juxt :id :label) (:nodes graph)))]
      (is (= #{["2" "source"] ["source" "stage"] ["stage" "prepared"]
               ["prepared" "pipeline"] ["pipeline" "result"]}
             (set (map (fn [{:keys [from to]}] [(labels from) (labels to)])
                       (:edges graph))))))))
