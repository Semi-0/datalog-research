(ns propagators.compiler-2-subagent-harness-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.compiler-2-combinator-test :as support]
            [propagators.datastructures.compound-object :as obj]))

(def orchestration-source
  "(load-lain \"examples/lain/subagents/composition.lain\")")

(def probe-source
  "(def-cells incoming-order incoming-control observed-status completed-result assessment rejected-command)
   (<-> (struct :assignments (struct :order incoming-order)
                :controls incoming-control
                :feedback (struct :errors rejected-command))
        (socket:observations socket))
   (<-> (struct :status observed-status :completion completed-result)
        (pi:observations pi))
   (<-> (struct :assessment assessment) (harbor:observations harbor))
   (define work-order (|> pi (slot :requests) (slot :execute) (slot :order)))
   (define feedback-order (|> socket (slot :requests) (slot :assignments) (slot :order)))
   (define control-request (|> pi (slot :requests) (slot :control)))
   (define status-feedback (|> socket (slot :requests) (slot :status)))
   (define offered-assignment (execute-assignment runtime))
   (define command-error (|> socket (slot :requests) (slot :errors)))
   (define runtime-result (assignment-result runtime))
   (define grading-input (|> harbor (slot :requests) (slot :grade)))
   (define user-result (|> user (slot :assignments) (slot :results)))")

(def controlled-policies
  ;; Pure fixture policies exercise connections, not real scheduling or delivery.
  ;; The socket/Pi inputs below already have the fixture's normalized field shape.
  "(define socket:normalize-commands (network [facts] facts))
   (define user:derive-assignments (slot :assignments))
   (define user:derive-controls (slot :controls))
   (define user:describe-commands (slot :feedback))
   (define user:describe-assignments
     (network [assignment-facts] (struct :assignments assignment-facts)))
   (define socket:encode-feedback (network [facts] facts))
   (define pi:normalize-observations (network [facts] facts))
   (define pi:derive-execution-requests
     (network [commands observations]
       (struct :execute (struct :order commands))))
   (define pi:derive-control-requests
     (network [controls observations] (struct :control controls)))
   (define pi:derive-completions (slot :completion))
   (define pi:derive-status
     (network [observations]
       (struct :status ((slot :status) observations))))
   (define evaluation:derive-trials
     (network [assignment-facts trials]
       (<-> assignment-facts trials)
       (list assignment-facts trials)))
   (define evaluation:incorporate-results
     (network [trials results output]
       (-> (struct :execution results) output)
       (list trials results output)))
   (define evaluation:select-pending-assignment
     (network [assignment-facts trials] ((slot :order) assignment-facts)))
   (define harbor:prepare
     (network [assignment-facts trials requests]
       (-> (struct :prepare assignment-facts) requests)
       (list assignment-facts trials requests)))
   (define harbor:grade
     (network [trials assessments requests]
       (-> (struct :grade ((slot :execution) trials)) requests)
       (list trials assessments requests)))
   (define harbor:observe-assessment
     (network [trials observations assessments]
       (-> ((slot :assessment) observations) assessments)
       (list trials observations assessments)))
   (define evaluation:derive-assignment-updates
     (network [trials assessments] (struct :results assessments)))")

(defn harness
  ([] (harness true))
  ([install-policies?]
   (let [session (support/session (str orchestration-source "\n" probe-source))]
     (when install-policies?
       (support/checked-load! session controlled-policies))
     session)))

(deftest actor-constructors-expose-partial-independent-interfaces
  (let [session (support/session
                 "(load-lain \"examples/lain/subagents/user-socket.lain\")
                  (load-lain \"examples/lain/subagents/runtime-pi.lain\")
                  (define first-user (make-user))
                  (define other-user (make-user))
                  (define runtime (make-runtime))
                  (define socket (make-socket))
                  (define pi (make-pi))
                  (-> :cancel-a (user:controls first-user))
                  (define other-control (user:controls other-user))
                  (define pending-result (runtime:result runtime))")]
    (doseq [[binding slots]
            [['first-user #{:assignments :controls :feedback}]
             ['runtime #{:commands :assignment-result :controls :status}]
             ['socket #{:requests :observations}]
             ['pi #{:requests :observations}]]]
      (is (= slots (set (obj/accessor-slot-keys (support/binding-value session binding))))))
    (is (value/nothing? (support/binding-value session 'other-control)))
    (is (value/nothing? (support/binding-value session 'pending-result)))))

(deftest actual-orchestration-accepts-late-policies-and-keeps-paths-distinct
  (let [session (harness false)]
    (support/publish! session 'incoming-order ::order 0 [:a :b])
    (is (value/nothing? (support/binding-value session 'work-order)))
    (support/checked-load! session controlled-policies)
    (is (= [:a :b] (support/binding-value session 'work-order)))
    (is (= [:a :b] (support/binding-value session 'feedback-order)))
    (support/publish! session 'incoming-order ::order 1 [:b :a])
    (is (= [:b :a] (support/binding-value session 'work-order)))
    (support/publish! session 'incoming-order ::order 0 [:a :b])
    (is (= [:b :a] (support/binding-value session 'work-order))
        "A stale source publication does not restore obsolete ordering")
    (support/publish! session 'observed-status ::status 0 :running)
    (is (= :running (support/binding-value session 'status-feedback)))
    (is (value/nothing? (support/binding-value session 'runtime-result))
        "A progress observation is not an assignment result")
    (is (value/nothing? (support/binding-value session 'grading-input)))
    (support/publish! session 'incoming-control ::control 0 :cancel-a)
    (is (= :cancel-a (support/binding-value session 'control-request)))
    (is (value/nothing? (support/binding-value session 'runtime-result))
        "Requesting cancellation does not fabricate a terminal result")
    (support/publish! session 'completed-result ::completion 0 :a-finished)
    (is (= :a-finished (support/binding-value session 'runtime-result)))
    (is (= :a-finished (support/binding-value session 'grading-input)))
    (is (value/nothing? (support/binding-value session 'user-result))
        "Runner completion still awaits Harbor assessment")
    (support/publish! session 'assessment ::assessment 0 :a-graded)
    (is (= :a-graded (support/binding-value session 'user-result)))
    (is (= [:b :a] (support/binding-value session 'feedback-order))
        "Graded feedback preserves current user ordering")
    (support/publish! session 'completed-result ::completion 0 :a-finished)
    (support/reevaluate! session)
    (is (= :a-finished (support/binding-value session 'grading-input)))
    (is (= :a-graded (support/binding-value session 'user-result)))))

(deftest recorded-inputs-replay-without-live-interpreters-and-sessions-stay-isolated
  (let [first-session (harness)
        second-session (harness)]
    (support/publish! first-session 'rejected-command ::rejection 0 :unsupported-command)
    (is (= :unsupported-command (support/binding-value first-session 'command-error)))
    (is (value/nothing? (support/binding-value first-session 'offered-assignment))
        "A rejected command produces feedback without fabricating an assignment")
    (is (value/nothing? (support/binding-value first-session 'control-request)))
    (is (value/nothing? (support/binding-value first-session 'runtime-result)))
    (support/publish! first-session 'incoming-order ::order 0 [:a])
    (support/publish! first-session 'observed-status ::status 0 :running)
    (support/publish! first-session 'completed-result ::completion 0 :a-finished)
    (support/publish! first-session 'assessment ::assessment 0 :a-graded)
    (is (value/nothing? (support/binding-value second-session 'work-order)))
    (is (value/nothing? (support/binding-value second-session 'runtime-result)))
    ;; Replay only these recorded publications into another declaration session.
    ;; No socket/Pi effect interpreter is installed in either session.
    (support/publish! second-session 'incoming-order ::order 0 [:a])
    (support/publish! second-session 'observed-status ::status 0 :running)
    (support/publish! second-session 'completed-result ::completion 0 :a-finished)
    (support/publish! second-session 'assessment ::assessment 0 :a-graded)
    (doseq [binding ['work-order 'status-feedback 'runtime-result 'grading-input 'user-result]]
      (is (= (support/binding-value first-session binding)
             (support/binding-value second-session binding))))
    (support/publish! second-session 'incoming-order ::order 1 [:b])
    (is (= [:a] (support/binding-value first-session 'work-order)))
    (is (= [:b] (support/binding-value second-session 'work-order)))))
