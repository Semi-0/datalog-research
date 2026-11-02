(ns propagators.compiler-2.runtime.boundary-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.runtime.session.commands :as runtime]
            [propagators.compiler-2.runtime.boundary.display :as display]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.compiler-2.runtime.inspection.trace.subscriptions :as subscriptions]
            [graph.compiler-2-semantic-repl :as semantic-repl]
            [propagators.cells.value :as value]
            [propagators.datastructures.support-collection :as collection]
            [propagators.cells.merge :as merge]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.semantic-trace :as semantic-trace]))

(defn- current-source-value [network id]
  (propagators.semantic-trace.capture/root-graph
   (data/payload (net/network-cell-strongest network id))))

(deftest display-normalizes-raw-values-without-changing-semantic-values
  (let [display-id :display
        first-update (display/update-value display-id display-id 1 1)
        second-update (display/update-value display-id display-id 2 2)
        content (collection/merge-content first-update second-update)
        graph (semantic-trace/graph-union
               {:nodes {:a "a"} :edges [] :values {}})]
    (is (= 2 (data/payload (merge/strongest-value content net/empty-net))))
    (is (= graph (data/payload (merge/strongest-value
                               (display/update-value display-id display-id 3 graph) net/empty-net))))
    (is (identical? second-update (display/update-value display-id display-id 4 second-update)))))

(deftest session-trace-facts-retain-the-publication-evidence
  (let [event (subscriptions/result-publication :subscription :target 0
                {:semantic-trace/graph true :nodes {:a "A"} :edges []})
        evidence (merge/strongest-value event net/empty-net)
        captured (data/payload evidence)
        graph (propagators.semantic-trace.capture/root-graph captured)
        row (propagators.semantic-trace.graph-facts/current
             graph (propagators.semantic-trace.graph-facts/assertion-id
                     (propagators.semantic-trace.capture/observation-id captured)
                     :root :scope/available [:root]))]
    (is (seq (propagators.datastructures.layered-value/support-of evidence)))
    (is (= (propagators.datastructures.layered-value/support-of evidence)
           (propagators.datastructures.layered-value/support-of row)))
    (is (= #{:a}
           (set (keys (:nodes (propagators.semantic-trace.high-level/display-graph captured))))))))

(deftest language-trace-publishes-reactively-without-tui
  (let [session (state/new-session)
        source "(let-cell [next traced]
                  (<-> ((network [x] (+ x 1)) 4) next)
                  (trace next traced)
                  traced)"
        _ (runtime/compile-source! session source)
        target-id (get-in @session [:compiled :cell])]
    (is (empty? (:tuis @session)))
    (is (= 1 (subscriptions/refresh-now! session)))
    (let [initial (current-source-value (:program/net @session) target-id)
          next-node (some (fn [[id label]] (when (= "next" label) id))
                          (:nodes initial))
          extra-node :headless/extra
          expanded (semantic-trace/graph-union
                    (:graph @session)
                    {:nodes {extra-node "extra"}
                     :edges [[extra-node next-node]]
                     :values {}})]
      (is (semantic-trace/semantic-trace-graph? initial))
      (is next-node)
      (swap! session assoc :graph expanded :program/epoch 1)
      (is (= 1 (subscriptions/refresh-now! session)))
      (let [updated (current-source-value (:program/net @session) target-id)]
        (is (contains? (set (semantic-repl/edge-labels updated))
                       ["extra" "next"]))
        (is (not (value/unusable? updated)))))))
