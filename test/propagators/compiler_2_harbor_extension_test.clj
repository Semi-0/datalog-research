(ns propagators.compiler-2-harbor-extension-test
  (:require [clojure.test :refer [deftest is testing]]
            [examples.lain.subagents.assignment-model :as assignments]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.harbor-extension :as harbor]
            [examples.lain.subagents.primitives :as primitives]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2-combinator-test :as support]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.runner :as runner]))

(def session-id "controlled-harbor")
(defn event [id order kind fields]
  (facts/fact session-id id order kind fields))
(defn log-of [& events] (facts/from-facts session-id events))
(defn submitted [id order assignment-id task]
  (event id order :assignment/submitted {:assignment-id assignment-id :task task}))
(defn context [id order assignment-id]
  (event id order :harbor/context {:assignment-id assignment-id :context {:workspace assignment-id}}))
(defn kinds [log] (mapv :kind (facts/ordered-facts log)))
(defn of-kind [log kind] (filterv #(= kind (:kind %)) (facts/ordered-facts log)))
(defn publish! [session name log]
  (let [state @session
        id (env/resolve-binding-id (:program/net state) (:program/env state) name)]
    (primitives/publish-facts! session session-id id log)))

(deftest preparation-and-priority-do-not-confuse-readiness-with-user-order
  (let [a (submitted "submit-a" 0 "a" "Task A")
        b (submitted "submit-b" 1 "b" "Task B")
        only-b-ready (log-of a b (context "context-b" 2 "b"))
        ready (facts/accumulate only-b-ready [(context "context-a" 3 "a")])
        claimed (facts/accumulate ready
                                  [(event "claim-a" 4 :runtime/requested {:assignment-id "a"})
                                   (event "reorder" 5 :assignment/reordered {:assignment-ids ["b" "a"]})])
        uncertain (facts/accumulate claimed
                                    [(event "uncertain-a" 6 :runtime/uncertain
                                            {:assignment-id "a" :failure :lost-launch-reply})])]
    (is (nil? (assignments/next-offer (assignments/derive-schedule only-b-ready))))
    (is (= "a" (:assignment-id (assignments/next-offer (assignments/derive-schedule ready)))))
    (is (= "a" (assignments/active-assignment-id (assignments/derive-schedule claimed))))
    (is (= ["b"] (assignments/pending-assignment-ids (assignments/derive-schedule claimed))))
    (is (= "a" (:assignment-id (assignments/next-offer (assignments/derive-schedule uncertain)))))
    (is (= (assignments/derive-schedule uncertain)
           (assignments/derive-schedule
            (facts/from-facts session-id (reverse (facts/ordered-facts uncertain))))))))

(deftest cancellation-distinguishes-pending-work-from-owned-execution
  (let [base (log-of (submitted "a" 0 "a" "Task A")
                     (submitted "b" 1 "b" "Task B")
                     (context "ca" 2 "a") (context "cb" 3 "b"))
        pending-cancel (facts/accumulate base
                                        [(event "cancel-a" 4 :assignment/cancel-requested
                                                {:assignment-id "a"})])
        claimed (facts/accumulate base
                                 [(event "claim-a" 4 :runtime/requested {:assignment-id "a"})
                                  (event "cancel-a" 5 :assignment/cancel-requested {:assignment-id "a"})])
        cancelled (facts/accumulate claimed
                                   [(event "stopped-a" 6 :runtime/cancelled
                                           {:assignment-id "a" :child "child-a" :cleanup-verified true})])]
    (is (= "b" (:assignment-id (assignments/next-offer (assignments/derive-schedule pending-cancel)))))
    (is (= "a" (:assignment-id (assignments/next-offer (assignments/derive-schedule claimed)))))
    (is (= "b" (:assignment-id (assignments/next-offer (assignments/derive-schedule cancelled)))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"verified cleanup"
         (assignments/derive-schedule
          (facts/accumulate claimed [(event "unverified" 6 :runtime/cancelled {:assignment-id "a"})]))))))

(deftest runner-completion-needs-independent-harbor-assessment
  (let [base (log-of (submitted "a" 0 "a" "Task A") (context "ca" 1 "a"))
        completed (facts/accumulate base
                                   [(event "done" 3 :runtime/completed
                                           {:assignment-id "a" :child "child-a" :result {:artifact "answer.txt"}})])
        requests (harbor/grading-requests completed (facts/empty-log session-id))
        assessment (event "grade" 4 :harbor/assessed {:assignment-id "a" :assessment {:reward 1}})
        graded (facts/accumulate completed [assessment])]
    (is (= [:harbor/grade-requested] (kinds requests)))
    (is (= {:artifact "answer.txt"} (:result (first (facts/ordered-facts requests)))))
    (is (empty? (of-kind (harbor/assignment-updates completed (facts/empty-log session-id))
                        :assignment/assessed)))
    (is (= {:reward 1}
           (:assessment (first (of-kind (harbor/assignment-updates graded (facts/empty-log session-id))
                                       :assignment/assessed)))))
    (is (empty? (facts/ordered-facts (harbor/grading-requests graded (facts/empty-log session-id)))))
    (is (= :assessed (:status (assignments/assignment-info (assignments/derive-schedule graded) "a"))))))

(deftest intent-identities-and-conflict-feedback-remain-monotonic
  (let [late (log-of (submitted "later-id" 5 "a" "Task A"))
        early (facts/accumulate late [(submitted "earlier-id" 1 "a" "Task A")])
        conflict (facts/accumulate early [(submitted "different-task" 6 "a" "Other task")])
        old-requests (harbor/preparation-requests late (facts/empty-log session-id))
        new-requests (harbor/preparation-requests early (facts/empty-log session-id))
        before (harbor/assignment-updates early (facts/empty-log session-id))
        after (harbor/assignment-updates conflict (facts/empty-log session-id))]
    (is (= old-requests new-requests)
        "Adding an earlier duplicate does not change an existing effect identity or payload")
    (is (nil? (assignments/next-offer (assignments/derive-schedule conflict))))
    (is (seq (of-kind after :assignment/rejected)))
    (is (facts/log? (facts/merge-logs before after)))
    (is (not (harbor/request-eligible? (first (facts/ordered-facts old-requests)) conflict)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"session"
         (harbor/incorporate-results late (facts/empty-log "foreign-session"))))))

(deftest a-late-task-conflict-does-not-release-owned-execution
  (let [claimed (log-of (submitted "a" 0 "a" "Task A")
                        (submitted "b" 1 "b" "Task B")
                        (context "ca" 2 "a") (context "cb" 3 "b")
                        (event "claim" 4 :runtime/requested {:assignment-id "a"}))
        conflicted (facts/accumulate claimed [(submitted "conflict" 5 "a" "Different task")])
        blocked (assignments/derive-schedule conflicted)
        completed (facts/accumulate conflicted
                                   [(event "done" 6 :runtime/completed {:assignment-id "a" :result :output})
                                    (event "late-start" 7 :runtime/started {:assignment-id "a" :child "child-a"})])
        released (assignments/derive-schedule completed)]
    (is (= "a" (assignments/active-assignment-id blocked)))
    (is (nil? (assignments/next-offer blocked)))
    (is (nil? (assignments/active-assignment-id released)))
    (is (= "b" (:assignment-id (assignments/next-offer released))))))

(deftest harbor-callbacks-are-explicit-and-preserve-grader-output
  (let [calls (atom [])
        callbacks {:prepare (fn [request] (swap! calls conj [:prepare (:assignment-id request)])
                              {:workspace "controlled"})
                   :grade (fn [request] (swap! calls conj [:grade (:result request)])
                            {:reward 0.75 :source :controlled-fixture})
                   :dispose! (fn [session] (swap! calls conj [:dispose session]))}
        source (log-of (submitted "a" 0 "a" "Task A"))
        prepare (first (facts/ordered-facts (harbor/preparation-requests source (facts/empty-log session-id))))
        prepared (first (harbor/interpret! callbacks prepare))
        completed (facts/accumulate source
                                   [(event "context" 1 (:kind prepared) (dissoc prepared :kind))
                                    (event "done" 2 :runtime/completed {:assignment-id "a" :result :output})])
        grade (first (facts/ordered-facts (harbor/grading-requests completed (facts/empty-log session-id))))]
    (is (= :harbor/context (:kind prepared)))
    (is (= [{:kind :harbor/assessed :assignment-id "a"
             :assessment {:reward 0.75 :source :controlled-fixture}}]
           (harbor/interpret! callbacks grade)))
    (is (= [] (harbor/interpret! callbacks {:operation :dispose :session session-id})))
    (is (= {:observations [] :receipt {:cleanup-verified true}}
           (harbor/interpret! {:dispose! (fn [_] {:cleanup-verified true})}
                              {:operation :dispose :session session-id})))
    (is (= [[:prepare "a"] [:grade :output] [:dispose session-id]] @calls))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No Harbor preparation"
                          (harbor/interpret! {} prepare)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"session"
                          (harbor/interpret-request! "another-session" callbacks prepare)))))

(deftest actual-framework-primitives-react-without-materializing-its-interface
  (binding [runner/*advance-transform*
            (runner/compose-advance-transforms runner/*advance-transform*
                                               (primitives/runner-transform session-id))]
    (let [session (-> (loader/load-session-from-source
                      "0" {:extensions [(harbor/session-extension session-id)]})
                     (support/checked-load!
                      "(load-lain \"examples/lain/subagents/evaluation-framework.lain\")
                       (define harbor (make-harbor))
                       (define framework (evaluation-framework-from-harbor harbor))
                       (def-cells input result observations)
                       (<-> input (assignments framework))
                       (<-> result (assignment-result framework))
                       (<-> observations (harbor:observations harbor))
                       (define offer (on-going-assignment framework))
                       (define requests (harbor:requests harbor))
                       (define updates (assignments framework))"))
          initial (log-of (submitted "a" 0 "a" "Task A") (submitted "b" 1 "b" "Task B"))
          ready (log-of (context "ca" 2 "a") (context "cb" 3 "b"))]
      (publish! session 'input initial)
      (is (value/nothing? (support/binding-value session 'offer)))
      (is (= 2 (count (of-kind (support/binding-value session 'requests) :harbor/prepare-requested))))
      (publish! session 'observations ready)
      (is (= "a" (:assignment-id (support/binding-value session 'offer))))
      (publish! session 'input
                        (facts/accumulate initial
                                          [(event "claim" 4 :runtime/requested {:assignment-id "a"})
                                           (event "reorder" 5 :assignment/reordered {:assignment-ids ["b" "a"]})]))
      (is (= "a" (:assignment-id (support/binding-value session 'offer))))
      (publish! session 'result
                        (log-of (event "done" 6 :runtime/completed
                                       {:assignment-id "a" :result {:artifact "answer"}})))
      (is (= "b" (:assignment-id (support/binding-value session 'offer))))
      (is (= 1 (count (of-kind (support/binding-value session 'requests) :harbor/grade-requested))))
      (is (empty? (of-kind (support/binding-value session 'updates) :assignment/assessed)))
      (publish! session 'observations
                        (facts/accumulate ready
                                          [(event "assessed" 7 :harbor/assessed
                                                  {:assignment-id "a" :assessment {:reward 1}})]))
      (is (= {:reward 1} (:assessment (first (of-kind (support/binding-value session 'updates)
                                                    :assignment/assessed))))))))
