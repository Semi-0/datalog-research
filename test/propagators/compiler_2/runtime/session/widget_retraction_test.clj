(ns propagators.compiler-2.runtime.session.widget-retraction-test
  (:require [clojure.test :refer [deftest is]]
            [graph.compiler-2-assembly :as assembly]
            [graph.xr-runtime :as xr]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]))

(deftest widget-can-retract-and-publish-again-with-ordered-history
  (let [session (assembly/new-session)]
    (xr/handle-command! session
                        {:op :xr/extend-graph
                         :source (str "(define events) (define out) (define widget) "
                                      "(-> events out) (slider-io \"gain\" out events widget)")})
    (xr/handle-command! session {:op :xr/widget-event :widget-id "gain"
                                 :channel "value" :value 42})
    (let [before @session
          event-cell (get-in before [:xr :widgets "gain" :channels "value" :event-cell])
          epoch (get-in before [:xr :widget-epochs "gain"])]
      (runtime/commit-runtime-input! session {:runtime/input :xr/widget-retraction
                                              :widget-id "gain" :channel "value"})
      (let [after @session
            receipt (last (:runtime/inputs after))]
        (is (= (inc epoch) (get-in after [:xr :widget-epochs "gain"])))
        (is (not (contains? (get-in after [:xr :widget-latest "gain"]) "value")))
        (is (= (inc (count (:runtime/inputs before))) (count (:runtime/inputs after))))
        (is (= :xr/widget-retraction (:runtime/input receipt)))
        (is (= "gain" (:widget-id receipt)))
        (is (= "value" (:channel receipt)))
        (is (= (inc epoch) (:epoch receipt)))
        (is (= 1 (count (:updates receipt))))
        (is (= event-cell (get-in receipt [:updates 0 :cell-id])))
        (is (some? (get-in receipt [:updates 0 :update]))))
      ;; Repeating an already absent channel remains a valid ordered input.
      (runtime/commit-runtime-input! session {:runtime/input :xr/widget-retraction
                                              :widget-id "gain" :channel "value"})
      (is (= (+ 2 epoch) (get-in @session [:xr :widget-epochs "gain"])))
      (xr/handle-command! session {:op :xr/widget-event :widget-id "gain"
                                   :channel "value" :value 17})
      (is (= (+ 3 epoch) (get-in @session [:xr :widget-epochs "gain"])))
      (is (= 17 (get-in @session [:xr :widget-latest "gain" "value"])))
      (is (= 17 (data/payload (net/network-cell-content (:program/net @session) event-cell)))))))
