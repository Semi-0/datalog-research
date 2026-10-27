(ns propagators.compiler-2-harness-primitives-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.primitives :as primitives]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.compiler-2-combinator-test :as support]
            [propagators.runner :as runner]))

(def session-id "primitive-test")

(defn event [id order fields]
  (facts/fact session-id id order :test/event fields))

(defn fixture-session []
  (let [bundle (extension/extension-bundle
                {:id ::policies :effects []
                 :bindings
                 [['echo-facts (primitives/leaf-operator
                                ::echo {:arity 1 :inputs [0] :input-kinds [:facts]
                                        :session session-id :kind :facts}
                                identity)]
                  ['current-choice (primitives/leaf-operator
                                    ::choice {:arity 1 :inputs [0] :input-kinds [:facts]
                                              :session session-id :kind :value}
                                    (fn [log] (:choice (last (facts/ordered-facts log)))))]]})]
    (binding [runner/*advance-transform*
              (runner/compose-advance-transforms runner/*advance-transform*
                                                  (primitives/runner-transform session-id))]
      (loader/load-session-from-source
       "(define incoming)
        (define echoed (echo-facts incoming))
        (define selected (current-choice incoming))"
       {:extensions [bundle]}))))

(defn publish! [session log]
  (let [state @session
        id (env/resolve-binding-id (:program/net state) (:program/env state) 'incoming)]
    (primitives/publish-facts! session session-id id log)))

(deftest fact-union-is-ordered-associative-commutative-idempotent-and-owned
  (let [a (facts/from-facts session-id [(event "a" 2 {:x 1})])
        b (facts/from-facts session-id [(event "b" 1 {:x 2})])
        c (facts/from-facts session-id [(event "c" 1 {:x 3})])]
    (is (= ["b" "c" "a"] (mapv :id (facts/ordered-facts (facts/merge-logs a (facts/merge-logs b c))))))
    (is (= (facts/merge-logs a b) (facts/merge-logs b a)))
    (is (= (facts/merge-logs a (facts/merge-logs b c))
           (facts/merge-logs (facts/merge-logs a b) c)))
    (is (= a (facts/merge-logs a a)))
    (is (= 1 (count (facts/ordered-facts a))))
    (is (thrown? Exception (facts/merge-logs a (facts/empty-log "foreign"))))
    (is (thrown? Exception (facts/accumulate a [(event "a" 2 {:x 99})])))))

(deftest fact-ingress-merges-without-global-domain-registration
  (let [session (fixture-session)
        a (facts/from-facts session-id [(event "a" 0 {:choice {:task "a"}})])
        b (facts/from-facts session-id [(event "b" 1 {:choice {:task "b"}})])]
    (publish! session a)
    (is (= ["a"] (mapv :id (facts/ordered-facts (support/binding-value session 'echoed)))))
    (let [before @session]
      (publish! session b)
      (is (= ["a" "b"] (mapv :id (facts/ordered-facts (support/binding-value session 'echoed)))))
      (is (= ["a"] (mapv :id (facts/ordered-facts (support/binding-value (atom before) 'echoed))))))
    (publish! session a)
    (is (= 2 (count (facts/ordered-facts (support/binding-value session 'echoed)))))
    (is (value/contradiction? (merge/cell-merge a b (:program/net @session)))
        "Outside the owning runner these domain leaves do not change global merge behavior")
    (let [before @session]
      (is (thrown? Exception (publish! session (facts/empty-log "foreign"))))
      (is (identical? before @session)))))

(deftest value-policy-observes-fact-input-partition
  (let [bundle (extension/extension-bundle
                {:id ::partition :effects []
                 :bindings
                 [['partition-counts
                   (primitives/leaf-operator
                    ::partition-counts
                    {:arity 2 :inputs [0 1] :input-kinds [:facts :facts]
                     :session session-id :kind :value}
                    (fn [left right]
                      {:left (count (facts/ordered-facts left))
                       :right (count (facts/ordered-facts right))}))]]})
        session (binding [runner/*advance-transform*
                          (runner/compose-advance-transforms
                           runner/*advance-transform* (primitives/runner-transform session-id))]
                  (loader/load-session-from-source
                   "(define left) (define right)
                    (define counts (partition-counts left right))"
                   {:extensions [bundle]}))
        publish-port! (fn [symbol log]
                        (let [state @session
                              id (env/resolve-binding-id (:program/net state) (:program/env state) symbol)]
                          (primitives/publish-facts! session session-id id log)))
        shared (facts/from-facts session-id [(event "shared" 0 {})])]
    (is (= {:left 0 :right 0} (support/binding-value session 'counts)))
    (publish-port! 'left shared)
    (is (= {:left 1 :right 0} (support/binding-value session 'counts)))
    (publish-port! 'right shared)
    (is (= {:left 1 :right 1} (support/binding-value session 'counts)))
    (publish-port! 'right shared)
    (is (= {:left 1 :right 1} (support/binding-value session 'counts)))))

(deftest current-offer-replaces-and-withdraws-instead-of-accumulating-history
  (let [session (fixture-session)]
    (is (value/nothing? (support/binding-value session 'selected)))
    (publish! session (facts/from-facts session-id [(event "a" 0 {:choice {:task "a"}})]))
    (is (= {:task "a"} (support/binding-value session 'selected)))
    (publish! session (facts/from-facts session-id [(event "b" 1 {:choice {:task "b"}})]))
    (is (= {:task "b"} (support/binding-value session 'selected)))
    (publish! session (facts/from-facts session-id [(event "done" 2 {:choice nil})]))
    (is (value/nothing? (support/binding-value session 'selected)))
    (is (= 3 (count (facts/ordered-facts (support/binding-value session 'echoed)))))))
