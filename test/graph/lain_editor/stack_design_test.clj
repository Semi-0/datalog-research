(ns graph.lain-editor.stack-design-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.runtime :as editor]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.closure-value :as closure]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.semantic-trace.capture :as capture]))

(defn- binding-value [session name]
  (let [state @session
        network (:program/net state)
        id (env/resolve-binding-id network (:program/env state) name)]
    (data/payload (net/network-cell-strongest network id))))

(deftest struct-stack-design-is-an-executable-partial-specification
  (let [source (str "(def-cells struct stack-slot stack-shape? scope-content? direct-children? "
                    "reference-integrity? root-compound-occurrence content-of-scope "
                    "direct-compound-children occurrence-identity begin-traversal "
                    "traversal-empty? take-pending pending-occurrence discovered-occurrence? "
                    "register-occurrence connect-reference connect-child-outputs "
                    "enqueue-child-work finish-traversal traversal-result)\n"
                    (slurp "examples/lain/top_down/stack-design.lain"))
        session (:session (editor/prepare source []))]
    (try
      (doseq [name '[stack stack-ref stack-identity stack-content stack-children
                    stack-constraints expand-pending scope->stack hierarchy->stacks
                    example-stage example-pipeline]]
        (is (closure/closure-info?
             (application/callable-declaration (binding-value session name)))
            (str name " is loaded from its domain file as a real network")))
      (is (= 15 (binding-value session 'example-result))
          "The concrete compound network evaluates independently of design holes")
      (is (capture/fragment? (binding-value session 'captured))
          "The example uses an actual frozen semantic capture")
      (is (= :stack-spec-2026-10-20-r4
             (binding-value session 'stack-specification-revision)))
      (is (not (value/nothing? (binding-value session 'stack-fixture-expectations))))
      (doseq [name '[struct stack-slot stack-shape? scope-content? direct-children?
                    reference-integrity? root-compound-occurrence content-of-scope
                    direct-compound-children occurrence-identity begin-traversal
                    traversal-empty? take-pending pending-occurrence
                    discovered-occurrence? register-occurrence connect-reference
                    connect-child-outputs enqueue-child-work finish-traversal
                    traversal-result]]
        (is (value/nothing? (binding-value session name))
            (str name " remains an explicit implementation obligation")))
      (is (value/nothing? (binding-value session 'root-occurrence)))
      (is (not (value/contradiction? (binding-value session 'design))))
      (finally
        (editor/close-session! session)))))
