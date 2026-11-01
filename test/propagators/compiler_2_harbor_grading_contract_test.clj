(ns propagators.compiler-2-harbor-grading-contract-test
  "W2 contract test: the eligibility chain that makes Harbor emit a grade request
  and the callback contracts that gate it. Facts, not redesign: every claim here
  mirrors harbor_extension.clj / assignment_model.clj / evaluation-framework.lain."
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

(def session-id "w2-grading-contract")

(defn event [id order kind fields]
  (facts/fact session-id id order kind fields))
(defn log-of [& events] (facts/from-facts session-id events))
(defn submitted [id order assignment-id task]
  (event id order :assignment/submitted {:assignment-id assignment-id :task task}))
(defn ctx [id order assignment-id]
  (event id order :harbor/context {:assignment-id assignment-id :context {:workspace assignment-id}}))
(defn completed [id order assignment-id result]
  (event id order :runtime/completed {:assignment-id assignment-id :result result}))
(defn kinds [log] (mapv :kind (facts/ordered-facts log)))
(defn of-kind [log kind] (filterv #(= kind (:kind %)) (facts/ordered-facts log)))
(defn status-of [log assignment-id]
  (:status (assignments/assignment-info (assignments/derive-schedule log) assignment-id)))
(defn publish! [session name log]
  (let [state @session
        id (env/resolve-binding-id (:program/net state) (:program/env state) name)]
    (primitives/publish-facts! session session-id id log)))

(defn prepared []
  (first (facts/ordered-facts
          (harbor/preparation-requests (log-of (submitted "s-a" 0 "a" "Task A"))
                                       (facts/empty-log session-id)))))

(defn graded [result]
  (let [source (log-of (submitted "s-a" 0 "a" "Task A") (ctx "c-a" 1 "a")
                       (completed "d-a" 2 "a" result))]
    [(first (facts/ordered-facts
             (harbor/grading-requests source (facts/empty-log session-id))))
     source]))

(deftest prepare-callback-must-return-a-context-map
  (testing "a map return becomes a :harbor/context observation"
    (let [seen (atom nil)
          prepared (harbor/interpret! {:prepare (fn [req] (reset! seen req) {:workspace "w"})}
                                      (prepared))]
      (is (= [{:kind :harbor/context :assignment-id "a" :context {:workspace "w"}}]
             prepared))
      (is (= #{:session :id :assignment-id :task} (set (keys @seen)))
          "the :prepare callback receives exactly these four request keys")
      (is (= "harbor/prepare/\"a\"" (:id @seen)))))
  (testing "any non-map return errors: the extension refuses to invent a context"
    (doseq [bad [nil "context" [:workspace] 42]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"context map"
                            (harbor/interpret! {:prepare (fn [_] bad)} (prepared)))
          (str "non-map prepare return: " (pr-str bad)))))
  (testing "no installed :prepare errors"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No Harbor preparation"
                          (harbor/interpret! {} (prepared))))))

(deftest grade-callback-must-return-an-assessment-map
  (let [[request source] (graded {:artifact "answer.txt"})]
    (testing "a map return becomes a :harbor/assessed observation; content is opaque"
      (let [seen (atom nil)
            observations (harbor/interpret! {:grade (fn [req] (reset! seen req) {:reward 0})}
                                            request)]
        (is (= [{:kind :harbor/assessed :assignment-id "a" :assessment {:reward 0}}]
               observations))
        (is (= #{:session :id :assignment-id :task :context :result} (set (keys @seen)))
            "the :grade callback receives exactly these six request keys")
        (is (= {:workspace "a"} (:context @seen))
            "the context is the exact map :prepare returned (via the schedule)")
        (is (= {:artifact "answer.txt"} (:result @seen))
            ":result originates from the runner completion, never from Harbor")))
    (testing "any non-map return errors"
      (doseq [bad [nil "graded" [:reward 1] 7]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"assessment map"
                              (harbor/interpret! {:grade (fn [_] bad)} request))
            (str "non-map grade return: " (pr-str bad)))))
    (testing "no installed :grade errors"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No Harbor grading"
                            (harbor/interpret! {} request))))
    (testing "grading needs both prepared context and a runner result"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"prepared context and runner result"
                            (harbor/interpret! {:grade (fn [_] {:reward 1})}
                                               (event "forged-1" 3 :harbor/grade-requested
                                                      {:assignment-id "a" :task "Task A"
                                                       :result :out})))
          "context missing")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"prepared context and runner result"
                            (harbor/interpret! {:grade (fn [_] {:reward 1})}
                                               (event "forged-2" 3 :harbor/grade-requested
                                                      {:assignment-id "a" :task "Task A"
                                                       :context {}})))
          ":result key missing (nil is legal only when the key is present)"))
    (is (= [:harbor/grade-requested] (kinds (harbor/grading-requests source (facts/empty-log session-id)))))))

(deftest host-side-assessment-bypassing-the-grade-callback-is-invisible
  (let [[request source] (graded {:artifact "answer"})
        _ request
        ;; A host driver computes the assessment itself and pushes it as an
        ;; observation of its own choosing — never returned from :grade.
        host-observation (event "host-grade" 4 :host/assessed
                                {:assignment-id "a" :assessment {:reward 1}})
        trials (facts/accumulate source [host-observation])]
    (testing "the assessment ingress keeps only :harbor/assessed facts"
      (is (empty? (facts/ordered-facts (harbor/assessment-facts trials (log-of host-observation))))
          "select-kinds #{:harbor/assessed} drops the host-side fact")
      (is (= [{:kind :harbor/assessed :assignment-id "a" :assessment {:reward 1}}]
             (harbor/interpret! {:grade (fn [_] {:reward 1})} request))
          "the same value returned from :grade IS a contract observation"))
    (testing "assignment facts never see the bypassed assessment"
      (let [assessments (harbor/assessment-facts trials (log-of host-observation))
            updates (harbor/assignment-updates trials assessments)]
        (is (empty? (of-kind updates :assignment/assessed))
            "no :assignment/assessed fact is derived from host-side grading")
        (is (= :completed (status-of trials "a"))
            "the schedule stays :completed; attach-assessment never fires")
        (is (not (contains? (assignments/assignment-info
                             (assignments/derive-schedule trials) "a")
                            :assessment)))
        (is (= 1 (count (facts/ordered-facts (harbor/grading-requests trials assessments))))
            "the grade request re-fires: the contract is still unmet")))
    (testing "the same value via the :grade path IS visible"
      (let [assessed-observation (event "assessed" 4 :harbor/assessed
                                        {:assignment-id "a" :assessment {:reward 1}})
            graded-log (facts/accumulate source [assessed-observation])
            assessments (harbor/assessment-facts graded-log (log-of assessed-observation))]
        (is (= 1 (count (facts/ordered-facts assessments))))
        (is (= {:reward 1}
               (:assessment (first (of-kind (harbor/assignment-updates graded-log assessments)
                                            :assignment/assessed)))))
        (is (= :assessed (status-of graded-log "a")))
        (is (empty? (facts/ordered-facts (harbor/grading-requests graded-log assessments))))))))

(deftest framework-level-host-side-assessment-does-not-reach-assignment-facts
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
                        (define requests (harbor:requests harbor))
                        (define updates (assignments framework))"))
          initial (log-of (submitted "s-a" 0 "a" "Task A"))
          prepared (log-of (ctx "c-a" 1 "a"))]
      (publish! session 'input initial)
      (publish! session 'observations prepared)
      (publish! session 'result (log-of (completed "d-a" 2 "a" {:artifact "answer"})))
      (is (= 1 (count (of-kind (support/binding-value session 'requests)
                               :harbor/grade-requested)))
          "submission + prepared context + runner result make the assignment grade-eligible")
      (publish! session 'observations
                (facts/accumulate prepared
                                  [(event "host-grade" 3 :host/assessed
                                          {:assignment-id "a" :assessment {:reward 1}})]))
      (is (empty? (of-kind (support/binding-value session 'updates) :assignment/assessed))
          "a host-side assessment pushed onto the observation port is invisible")
      (is (= 1 (count (of-kind (support/binding-value session 'requests)
                               :harbor/grade-requested)))
          "the grade request persists")
      (publish! session 'observations
                (facts/accumulate prepared
                                  [(event "assessed" 4 :harbor/assessed
                                          {:assignment-id "a" :assessment {:reward 1}})]))
      (is (= {:reward 1}
             (:assessment (first (of-kind (support/binding-value session 'updates)
                                          :assignment/assessed))))
          "the identical value returned through the :grade contract becomes visible")
      (is (not (value/nothing? (support/binding-value session 'updates)))))))
