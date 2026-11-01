(ns graph.lain-editor.inspection.harness-assignment-withdrawal-test
  (:require [clojure.test :refer [deftest is testing]]
            [examples.lain.subagents.harness-session :as harness]
            [graph.lain-editor.inspection.structural-value :as structure]
            [graph.lain-editor.runtime.display :as display]
            [propagators.cells.value :as value]
            [propagators.compiler-2-harness-session-test :as fixture]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.ids :as ids]
            [propagators.compiler-2.runtime.operators.network-history :as history-operator]
            [propagators.datastructures.compound-object :as object]
            [propagators.experimental.network-history :as history]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.visualization.extension :as visualization]
            [propagators.network :as net]
            [propagators.relationship-observer :as relationships]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.observation :as observation]
            [propagators.semantic-trace.retention :as retention]))

(defn- assignment-view [owner]
  (runtime/refresh-trace-subscriptions! (:session owner))
  (let [network (:program/net @(:session owner))
        replay (data/payload (net/network-cell-strongest network display/cell-id))
        graph (capture/root-graph (:graph (last (history/field replay :replay/frames))))
        [component _] (first (filter #(= "evaluation-framework" (str (val %))) (:nodes graph)))
        actor (data/payload (get (:values graph) component))
        reader (structure/snapshot-reader graph (first component))
        local (relationships/network-at-path network (first component))
        parents (vec (object/accessor-parent-ids actor :on-going-assignment))]
    {:read (structure/slot actor :on-going-assignment reader)
     :live (mapv #(net/network-cell-strongest local %) parents)
     :captured (mapv #(-> (reader %) :value) parents)}))

(defn- assert-current-assignment [owner expected]
  (let [{:keys [read live captured]} (assignment-view owner)]
    (is (<= 2 (count live)) "Exercise aliases, not a single accessor")
    (is (= live captured) "The frozen trace records the current live values")
    (is (:found? read) "All aliases agree; no false ambiguous field")
    (is (every? #(= expected (data/payload %)) live)
        "Completion and recovery reach every alias")))

(deftest completed-assignment-withdraws-through-live-accessor-links
  (let [running? (atom true)
        {:keys [options calls]} (fixture/fixtures "assignment-withdrawal" running?)
        owner (harness/open!
               "assignment-withdrawal"
               (assoc options :extensions
                      [[retention/extension {:outbox-id (ids/boundary-outbox-id)}]
                       [visualization/extension {:outbox-id (ids/boundary-outbox-id)}]
                       [display/extension {}]
                       [history-operator/extension {}]
                       [observation/extension {}]]))]
    (try
      (testing "initial availability"
        (assert-current-assignment owner value/nothing))
      (fixture/submit! owner "submit-a" 0 :assignment/submitted
                       {:assignment-id "a" :task "a"})
      (harness/pump! owner)
      (testing "running assignment"
        (let [current (data/payload (first (:live (assignment-view owner))))]
          (is (= "a" (:assignment-id current)))
          (assert-current-assignment owner current)))
      (reset! running? false)
      (harness/poll! owner "finish-a")
      (testing "completed assignment"
        (assert-current-assignment owner value/nothing))
      (reset! running? true)
      (fixture/submit! owner "submit-b" 100 :assignment/submitted
                       {:assignment-id "b" :task "b"})
      (harness/pump! owner)
      (testing "next assignment recovers"
        (let [current (data/payload (first (:live (assignment-view owner))))]
          (is (= "b" (:assignment-id current)))
          (assert-current-assignment owner current)))
      (is (= 2 (count (fixture/launch-calls calls))))
      (finally (harness/close! owner)))))
