(ns graph.lain-editor.constraint-checkpoint-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.constraint-checkpoint :as checkpoint]
            [propagators.compiler-2.runtime :as runtime]))

(def revision :stack-spec-2026-10-20-r1)

(defn outcome [id status required?]
  {:constraint/id id :specification/revision revision :status status
   :required? required? :dependencies [] :support #{} :reason (name status)})

(deftest checkpoint-rates-keep-pending-and-contradictory-required-work-visible
  (let [session (runtime/new-session)
        _ (runtime/register-tui! session {:client-id "experiment"
                                          :mode :versioned-premise})
        outcomes [(outcome :shape :satisfied true)
                  (outcome :children :pending true)
                  (outcome :reference :contradictory true)
                  (outcome :old-reader :withdrawn false)]
        captured (checkpoint/checkpoint
                  {:id :checkpoint-1 :specification-revision revision
                   :state @session :outcomes outcomes :membership-complete? false
                   :fixture-expectations {:shape :satisfied :children :pending}
                   :diagnostics [] :evidence [] :timing {}})]
    (is (= {:required-count 3 :membership-complete? false
            :satisfied 1 :violated 0 :pending 1 :contradictory 1
            :inactive-or-withdrawn 1 :required-constraint-satisfaction 1/3}
           (checkpoint/constraint-summary captured)))
    (is (= 1 (get-in captured [:fixture/summary :fixture-expectation-agreement])))
    (is (pos? (:checkpoint/capture-ns captured)))
    (is (some? (checkpoint/network-state captured)))
    (is (not (contains? (checkpoint/portable-summary captured) :network/state)))))

(deftest zero-required-is-not-applicable-and-invalid-revisions-fail
  (let [session (runtime/new-session)
        _ (runtime/register-tui! session {:client-id "experiment"
                                          :mode :versioned-premise})
        captured (checkpoint/checkpoint
                  {:id :empty :specification-revision revision :state @session
                   :outcomes [(outcome :retired :inactive false)]
                   :membership-complete? true :fixture-expectations {}
                   :diagnostics [] :evidence [] :timing {}})]
    (is (nil? (get-in captured [:constraint/summary
                                :required-constraint-satisfaction])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"revision mismatch"
                          (checkpoint/checkpoint
                           {:id :bad :specification-revision revision :state @session
                            :outcomes [(assoc (outcome :bad :pending true)
                                              :specification/revision :other)]
                            :membership-complete? false :fixture-expectations {}})))))
