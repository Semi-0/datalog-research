(ns propagators.experimental.rule-fixedpoint-validation-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [examples.lain.relational-datalog.proof-relation :as relation]
            [examples.lain.rule-fixedpoint.catalog :as catalog]
            [examples.lain.rule-fixedpoint.engine :as engine]
            [examples.lain.rule-fixedpoint.fixtures :as fixtures]
            [examples.lain.rule-fixedpoint.matching :as matching]
            [examples.lain.rule-fixedpoint.model :as model]
            [examples.lain.rule-fixedpoint.query :as query]
            [examples.lain.rule-fixedpoint.solvers :as solvers]
            [examples.lain.rule-fixedpoint.validation-reference :as reference]
            [examples.lain.amb.checkpoint-search :as checkpoint]
            [propagators.cells.value :as value]))

(defn completed [world]
  (let [result (engine/run world)]
    (if (= :completed (:status result)) result (throw (:error result)))))

(defn proof-content [world]
  (into {} (map (fn [[predicate id]]
                 [predicate (:proofs (relation/view (:network world) id))]))
        (get-in world [:cells :relations])))

(defn lifecycle [built]
  (let [active (completed (engine/seed built))
        withdrawn (completed (engine/inject active :edge-0 value/nothing
                                            {:timestamp 2 :status :retracted}))
        restored (completed (engine/inject withdrawn :edge-0 [0 1]
                                           {:timestamp 3 :status :active}))]
    [active withdrawn restored (completed restored)]))

(deftest omitted-lain-guard-is-an-explicit-unconditional-rule
  (let [compiled (catalog/compile-source
                  (slurp (io/resource "examples/lain/rule_fixedpoint/reach.lain")))
        rule (second (:rules compiled))]
    (is (nil? (:guard rule)))
    (is (= rule (model/step-rule :reach :reach :edge)))
    (is (= rule (model/step-rule :reach :reach :edge nil)))))

(deftest unconditional-indexed-rules-never-instantiate-a-compiled-question
  (let [rule (model/step-rule :answer :left :right)
        request {:rule rule :variables (model/variables rule) :demand {:x 0}
                 :initial? false :require-delta? true
                 :tables [#{[0 1] [1 2] [2 3]} #{[0 1] [1 2] [2 3]}]
                 :deltas [#{[0 1]} #{}]}]
    (with-redefs [query/instantiate (fn [& _] (throw (ex-info "Unexpected compiled validation" {})))]
      (doseq [mode [:indexed-full :indexed-delta]]
        (is (= #{{:x 0 :y 1 :z 2}} (:bindings (solvers/matching-result mode request {}))))
        (is (empty? (:bindings (solvers/matching-result mode (assoc request :demand {:x 2}) {}))))
        (is (empty? (:bindings (solvers/matching-result mode (assoc request :deltas [#{} #{}]) {}))))))))

(deftest required-delta-is-enforced-without-compiled-validation
  (let [rule (model/step-rule :answer :left :right)
        request {:rule rule :variables (model/variables rule) :demand {} :domain [0 1 2 3]
                 :tables [#{[0 1] [1 2]} #{[1 2] [2 3]}] :deltas [#{[0 1]} #{}]
                 :require-delta? true}
        template (query/compile-template rule {})]
    (doseq [mode [:indexed-full :indexed-delta] initial? [true false]]
      (let [request (assoc request :initial? initial? :query template)
            fast (solvers/matching-result mode request {})
            checked (reference/matching-result mode request)]
        (is (= #{{:x 0 :y 1 :z 2}} (:bindings fast)))
        (is (= (:bindings checked) (:bindings fast)))))))

(deftest fast-path-preserves-physical-proof-identities-and-withdrawal
  (let [fixture (fixtures/diamond 1)
        rules [(model/copy-rule :edge :reach) (model/step-rule :reach :reach :edge)]
        options (solvers/attach rules {:strategy :indexed-delta :domain (:domain fixture)} {})
        built (engine/prepare rules options (:sources fixture))
        fast (lifecycle built)
        checked (with-redefs [solvers/matching-result (fn [mode request _context]
                                                       (reference/matching-result mode request))]
                  (lifecycle built))]
    (doseq [[fast checked] (map vector fast checked)]
      (is (= (proof-content checked) (proof-content fast)))
      (is (= (get-in checked [:cells :sources]) (get-in fast [:cells :sources])))
      (is (= (:topology (engine/metrics checked)) (:topology (engine/metrics fast)))))
    (is (= (:network (nth fast 2)) (:network (nth fast 3))))
    (is (contains? (engine/facts (second fast) :reach) [0 3]))))

(deftest explicit-lain-guards-are-not-assumed-unconditional
  (let [rule (model/step-rule :answer :left :right "(network (x y z) false)")
        request {:rule rule :variables (model/variables rule) :demand {} :domain [0 1 2]
                 :tables [#{[0 1]} #{[1 2]}] :deltas [#{[0 1]} #{[1 2]}]
                 :initial? true :query (query/compile-template rule {})}
        checks (atom 0)]
    (binding [query/*candidate-checks* checks]
      (is (empty? (:bindings (solvers/matching-result :indexed-delta request {})))))
    (is (pos? @checks))))

(defn guard-request [guard]
  (let [rule (model/step-rule :answer :left :right guard)]
    {:rule rule :variables (model/variables rule) :demand {} :domain [1 2 3 4]
     :tables [#{[1 2]} #{[2 3] [2 4]}] :deltas [#{[1 2]} #{[2 3] [2 4]}]
     :initial? true :query (query/compile-template rule {})}))

(deftest batched-guards-preserve-selectivity-and-reuse-an-immutable-checkpoint
  (doseq [guard ["(network (x y z) true)" "(network (x y z) false)"
                 "(network (x y z) (= (+ x y) z))"]]
    (let [request (guard-request guard)
          ready (query/ready-question (:query request) request)
          checked (query/instantiate (:query request) request)]
      (doseq [tuple [[1 2 3] [1 2 4] [1 2 3]]]
        (is (= (query/accepted? checked tuple) (query/accepted-batch? ready tuple))))
      (doseq [mode [:indexed-full :indexed-delta]]
        (is (= (:bindings (reference/matching-result mode request))
               (:bindings (solvers/matching-result mode request {}))))))))

(deftest batching-runs-propagation-once-for-a-fully-bound-candidate
  (let [request (guard-request "(network (x y z) (= (+ x y) z))")
        ready (query/ready-question (:query request) request)
        original checkpoint/settle calls (atom 0)]
    (with-redefs [checkpoint/settle (fn [state] (swap! calls inc) (original state))]
      (is (true? (query/accepted-batch? ready [1 2 3])))
      (is (= 1 @calls))
      (reset! calls 0)
      (is (true? (query/accepted? ready [1 2 3])))
      (is (= 4 @calls)))
    (is (thrown? clojure.lang.ExceptionInfo (query/accepted-batch? ready [1 2])))
    (is (thrown? clojure.lang.ExceptionInfo (query/accepted-batch? ready [1 2 3 4])))))

(deftest unresolved-batched-guards-remain-errors-not-false-predicates
  (let [request (guard-request "(network (x y z) (let-cell [pending] pending))")
        ready (query/ready-question (:query request) request)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"remained unresolved"
                          (query/accepted-batch? ready [1 2 3])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"remained unresolved"
                          (query/accepted? ready [1 2 3])))))

(deftest batched-guard-keeps-lexical-capture-and-physical-proof-support
  (let [compiled (catalog/compile-source
                  "(let-cell [] (define ceiling 4)
                     (rule-set (step-rule :answer :left :right
                       (network (x y z) (and (< z ceiling) (= (+ x y) z))))))")
        rules (:rules compiled)
        specs {:a {:predicate :left :tuple [1 2]}
               :b {:predicate :right :tuple [2 3]}
               :c {:predicate :right :tuple [2 4]}}
        options (solvers/attach rules {:strategy :indexed-delta :domain [1 2 3 4]} compiled)
        built (engine/prepare rules options specs)
        optimized (completed (engine/seed built))
        checked (with-redefs [solvers/matching-result (fn [mode request _context]
                                                       (reference/matching-result mode request))]
                  (completed (engine/seed built)))]
    (is (= #{[1 3]} (engine/facts optimized :answer)))
    (is (= (proof-content checked) (proof-content optimized)))
    (is (= (:topology (engine/metrics checked)) (:topology (engine/metrics optimized))))))

(deftest empty-joins-do-not-initialize-a-guard
  (let [request (guard-request "(network (x y z) true)")
        request (assoc request :tables [#{[1 2]} #{[3 4]}])]
    (with-redefs [query/ready-question (fn [& _] (throw (ex-info "Unexpected guard setup" {})))]
      (is (empty? (:bindings (solvers/matching-result :indexed-delta request {})))))))

(deftest indexed-fast-path-matches-independent-tuple-enumeration
  (doseq [body [[[:p [:x :y]] [:q [:y :z]]]
                [[:p [:x :x]] [:p [:x :z]]]]
          tables [[#{[0 1] [1 2]} #{[1 2] [2 3]}]
                  [#{[0 0] [1 1] [0 1]} #{[0 2] [1 3]}]
                  [#{} #{[1 2]}]]
          demand [{} {:x 0} {:x 99}]
          initial? [true false] require-delta? [true false]]
    (let [rule (model/checked-rule {:id :checked :head [:answer [:x :z]] :body body})
          request {:rule rule :variables (model/variables rule) :domain [0 1 2 3]
                   :demand demand :initial? initial? :require-delta? require-delta?
                   :tables tables :deltas [(set (filter #(zero? (first %)) (first tables))) #{}]}
          expected (into #{} (filter #(model/accepts? request (mapv % (:variables request))))
                         (model/full-bindings request))]
      (doseq [mode [:indexed-full :indexed-delta]]
        ;; Delta without an explicit requirement intentionally admits only changed
        ;; candidates after startup; compare the same changed-window semantics.
        (let [effective (if (and (= :indexed-delta mode) (not initial?))
                          (assoc request :require-delta? true) request)
              expected (if (= effective request) expected
                           (into #{} (filter #(model/accepts? effective (mapv % (:variables request))))
                                 (model/full-bindings request)))]
          (is (= expected (:bindings (solvers/matching-result mode request {})))))))))
