(ns propagators.compiler-2-lazy-conditional-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [graph.lain-editor.runtime :as editor]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.declarations :as declarations]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.input :as input]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.network-builder :as builder]
            [propagators.runner :as runner]))

(use-fixtures :each
  (fn [test]
    (let [steps (atom 0)]
      (binding [runner/*advance-transform*
                (runner/compose-advance-transforms
                 runner/*advance-transform*
                 (fn [advance]
                   (fn [state continuations]
                     (when (> (swap! steps inc) 30000)
                       (throw (ex-info "Conditional did not reach quiescence" {})))
                     (advance state continuations))))]
        (test)))))

(defn- base [candidate] (data/payload candidate))

(defn- evaluate [source]
  (let [compiled (compiler/compile-source source)
        network (builder/run-propagators (:net compiled) (:props compiled))]
    (base (net/network-cell-strongest network (:cell compiled)))))

(defn- read-cell [pane symbol]
  (let [state @(:session pane)
        id (env/resolve-binding-id (:program/net state) (:program/env state) symbol)]
    (net/network-cell-strongest (:program/net state) id)))

(defn- publish! [pane symbol version base status]
  (let [state @(:session pane)
        id (env/resolve-binding-id (:program/net state) (:program/env state) symbol)]
    (input/commit-runtime-input!
     (:session pane)
     {:runtime/input :cell-message :cell-id id
      :update (membership/content
               {:base base :membership #{}
                :support #{{:source id :timestamp version :premises-status status}}})})))

(defn- observe-declarations [f]
  (let [calls (atom []) declare declarations/declare-application-context]
    (with-redefs [declarations/declare-application-context
                  (fn [state expression]
                    (swap! calls conj (ast/name (ast/operator expression)))
                    (declare state expression))]
      (f calls))))

(deftest if-and-cond-defer-recursion-and-capture-lexical-cells
  (doseq [body ["(if (<= n 0) acc (sum-to (- n 1) (+ acc n)))"
               "(cond [(<= n 0) acc else (sum-to (- n 1) (+ acc n))])"]]
    (is (= 10 (evaluate (str "(let-cell [sum-to] (define sum-to (network [n acc] " body ")) (sum-to 4 0))")))))
  (is (= 42 (evaluate "(let [x 40] (if true (+ x 2) (missing x)))")))
  (is (= false (evaluate "(if true false 42)"))))

(deftest cond-short-circuits-predicates-and-unselected-bodies
  (observe-declarations
   (fn [calls]
     (is (= 42 (evaluate "(cond [true (+ 40 2) (missing-predicate 1) (missing-body 1) else (missing-else 1)])")))
     (is (some #{'+} @calls))
     (is (not-any? #{'missing-predicate 'missing-body 'missing-else} @calls))))
  (observe-declarations
   (fn [calls]
     (is (= value/nothing (evaluate "(cond [false (missing-body 1)])")))
     (is (not-any? #{'missing-body} @calls))))
  (is (= 2 (evaluate "(cond [false 1 true 2 else 3])"))))

(deftest pending-condition-and-branch-switching-use-current-support
  (observe-declarations
   (fn [calls]
     (let [pane (editor/prepare "(def-cells flag) (define x 7) (define result (if flag (+ x 1) (* x 2)))" [])]
       (try
         (is (= value/nothing (base (read-cell pane 'result))))
         (is (not-any? #{'+ '*} @calls))
         (publish! pane 'flag 1 true :active)
         (is (= 8 (base (read-cell pane 'result))))
         (is (some #{'+} @calls))
         (is (not-any? #{'*} @calls))
         (publish! pane 'flag 2 false :active)
         (is (= false (base (read-cell pane 'flag))))
         (is (= 14 (base (read-cell pane 'result))))
         (is (some #{'*} @calls))
         (publish! pane 'flag 3 value/nothing :retracted)
         (is (= value/nothing (base (read-cell pane 'result))))
         (reset! calls [])
         (publish! pane 'flag 4 true :active)
         (is (= 8 (base (read-cell pane 'result))))
         (is (not-any? #{'+ '*} @calls) "Previously built branches reuse their topology")
         (is (= #{4} (set (map :timestamp (datum/support-of (read-cell pane 'result))))))
         (finally (editor/close-session! (:session pane))))))))

(deftest cond-waits-and-switches-in-clause-order
  (observe-declarations
   (fn [calls]
     (let [pane (editor/prepare
                 "(def-cells first-flag second-flag)
                  (define result (cond [first-flag (+ 1 2)
                                        second-flag (* 3 4)
                                        else (- 9 1)]))" [])]
       (try
         (publish! pane 'second-flag 1 true :active)
         (is (= value/nothing (base (read-cell pane 'result))))
         (is (not-any? #{'+ '* '-} @calls))
         (publish! pane 'first-flag 1 true :active)
         (is (= 3 (base (read-cell pane 'result))))
         (is (not-any? #{'* '-} @calls))
         (publish! pane 'first-flag 2 false :active)
         (is (= 12 (base (read-cell pane 'result))))
         (is (not-any? #{'-} @calls))
         (publish! pane 'second-flag 2 false :active)
         (is (= 8 (base (read-cell pane 'result))))
         (reset! calls [])
         (publish! pane 'first-flag 3 true :active)
         (is (= 3 (base (read-cell pane 'result))))
         (is (not-any? #{'+ '* '-} @calls))
         (finally (editor/close-session! (:session pane))))))))

(deftest contradictory-condition-is-visible-and-can-recover
  (observe-declarations
   (fn [calls]
     (let [pane (editor/prepare "(def-cells flag) (define result (if flag (+ 1 2) (* 3 4)))" [])]
       (try
         (publish! pane 'flag 1 value/contradiction :active)
         (is (value/contradiction? (base (read-cell pane 'result))))
         (is (not-any? #{'+ '*} @calls))
         (publish! pane 'flag 2 false :active)
         (is (= 12 (base (read-cell pane 'result))))
         (publish! pane 'flag 3 value/contradiction :active)
         (is (value/contradiction? (base (read-cell pane 'result))))
         (publish! pane 'flag 4 true :active)
         (is (= 3 (base (read-cell pane 'result))))
         (finally (editor/close-session! (:session pane))))))))
