(ns gx10-benchmark.outcome-test
  (:require [clojure.test :refer [deftest is testing]]
            [gx10-benchmark.outcome :as outcome]))

(def normal {:events [{:type :assistant-end :stop :stop :request 1} {:type :agent-end}]
             :exit-code 0 :final-response? true})
(def recovered
  (assoc normal :events [{:type :anti-repeat :action :corrected :kind :reasoning-repeat :request 1}
                         {:type :assistant-end :stop :aborted :request 1}
                         {:type :assistant-end :stop :tool-use :request 2}
                         {:type :assistant-end :stop :stop :request 3}
                         {:type :agent-end}]))
(def evaluation {:assertions 14 :expected-assertions 14 :failures 0 :errors 0 :exit-code 0})

(deftest stream-ending-evidence
  (is (= :normal (outcome/execution-state normal)))
  (is (= :recovered (outcome/execution-state recovered)))
  (is (= :runtime-error (outcome/execution-state (update recovered :events subvec 1))))
  (is (= :runtime-error (outcome/execution-state (assoc recovered :final-response? false))))
  (is (= :runtime-error (outcome/execution-state (assoc normal :events [{:type :assistant-end :stop :length} {:type :agent-end}])))))

(deftest recovery-must-not-conceal-other-errors
  (is (= :runtime-error (outcome/execution-state (update recovered :events conj {:type :error}))))
  (is (= :runtime-error (outcome/execution-state
                         (assoc-in recovered [:events 0 :request] 999))))
  (is (= :repetition-stopped
         (outcome/execution-state
          (update recovered :events conj {:type :anti-repeat :action :stopped :kind :reasoning-repeat})))))

(deftest independent-dimensions
  (let [result (outcome/result recovered evaluation {} {:swap-delta 4096})]
    (is (= :passed (:outcome result)))
    (is (false? (:workflow? result)))
    (is (false? (:timing-valid? result)))
    (is (= [true true true] (outcome/queue-facts (assoc result :durable? true :cleanup-verified? true))))
    (is (= [false true true] (outcome/queue-facts (assoc result :durable? true :cleanup-verified? false)))))
  (doseq [invalid [(assoc evaluation :assertions 0) (assoc evaluation :failures 1)
                   (assoc evaluation :errors 1) (assoc evaluation :assertions 13)
                   (assoc evaluation :exit-code 1) {}]]
    (is (not (outcome/assertions-pass? invalid)))))

(deftest missing-resource-measurements-do-not-establish-valid-timing
  (is (false? (:timing-valid? (outcome/result normal evaluation {} {}))))
  (is (true? (:timing-valid? (outcome/result normal evaluation {}
                                             {:measurements-complete? true :swap-delta 0})))))

(deftest no-elapsed-time-nontermination-claim
  (is (= :runtime-error (outcome/execution-state
                         (assoc normal :final-response? false :nontermination-evidence {:recognized? true}))))
  (is (= :nonterminating (outcome/execution-state
                          (assoc normal :nontermination-evidence
                                 {:recognized? true :matching-command-active? true})))))
