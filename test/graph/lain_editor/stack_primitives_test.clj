(ns graph.lain-editor.stack-primitives-test
  (:require [clojure.test :refer [deftest is testing]]
            [graph.lain-editor.runtime :as editor]
            [graph.lain-editor.stack-primitives :as stack]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.datastructures.compound-object :as object]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.semantic-trace.capture :as capture]))

(def observation [:observation 1])
(def root-occurrence [observation [:outer :root-call]])
(def child-occurrence [observation [:outer :child-call]])

(defn- graph [nested]
  {:semantic-trace/graph true
   :nodes {} :node-kinds {} :values {} :edges []
   :semantic-capture/nested-scopes nested})

(defn- captured [root-nested scopes]
  (capture/fragment
   {:observation observation
    :scopes (assoc scopes :root (graph root-nested))}))

(defn- linked-items [candidate]
  (loop [current candidate items []]
    (if (= :compiler-2/list-empty current)
      items
      (recur (object/accessor-source-slot-value current :cdr)
             (conj items (object/accessor-source-slot-value current :car))))))

(deftest frozen-readers-preserve-observation-qualified-identities
  (let [child-graph (graph {})
        source (captured {[:outer :root-call] root-occurrence}
                         {root-occurrence (graph {[:outer :child-call] child-occurrence})
                          child-occurrence child-graph})]
    (is (= root-occurrence (stack/root-compound-occurrence source)))
    (is (= root-occurrence (stack/occurrence-identity source root-occurrence)))
    (is (= child-graph (stack/content-of-scope source child-occurrence)))
    (is (= [child-occurrence]
           (linked-items (stack/direct-compound-children source root-occurrence))))
    (is (value/contradiction?
         (stack/occurrence-identity source [[:other-observation] [:outer :root-call]])))))

(deftest root-reader-reports-missing-and-ambiguous-membership
  (testing "complete empty root membership is not silently treated as pending success"
    (is (= :missing-root
           (:stack/error
            (first (value/contradiction-provenance
                    (stack/root-compound-occurrence (captured {} {}))))))))
  (testing "multiple roots remain an explicit diagnostic"
    (let [other [observation [:outer :other-call]]
          result (stack/root-compound-occurrence
                  (captured {[:outer :root-call] root-occurrence
                             [:outer :other-call] other} {}))]
      (is (value/contradiction? result))
      (is (= :ambiguous-roots
             (:stack/error (first (value/contradiction-provenance result))))))))

(defn- binding-value [session name]
  (let [state @session
        network (:program/net state)
        id (env/resolve-binding-id network (:program/env state) name)]
    (data/payload (net/network-cell-strongest network id))))

(deftest live-struct-fields-refine-and-conflict-independently
  (let [source (str "(def-cells struct input) "
                    "(-> stack:struct struct) "
                    "(define candidate (struct (list (list :x input) (list :y 2)))) "
                    "(define x (p:slot :x candidate)) "
                    "(define y (p:slot :y candidate))")
        session (:session (editor/prepare source []))]
    (try
      (is (value/nothing? (binding-value session 'x)))
      (is (= 2 (binding-value session 'y)))
      (editor/load-checked! session "(-> 1 input)")
      (is (= 1 (binding-value session 'x)))
      (is (= 2 (binding-value session 'y)))
      (editor/load-checked! session "(-> 1 input)")
      (is (= 1 (binding-value session 'x)) "equal evidence is idempotent")
      (editor/load-checked! session "(-> 3 input)")
      (is (value/contradiction? (binding-value session 'x)))
      (is (= 2 (binding-value session 'y)) "the unrelated field remains usable")
      (finally
        (editor/close-session! session)))))

(defn- slot [candidate key]
  (object/accessor-source-slot-value candidate key))

(deftest immutable-traversal-preserves-children-sharing-and-cycles
  (testing "a first occurrence expands and a repeated occurrence becomes a reference"
    (let [s0 (stack/begin-traversal observation root-occurrence)
          [root-work s1] (stack/take-pending s0)
          [_ s2] (stack/register-occurrence s1 root-work root-occurrence :root-content)
          s3 (stack/connect-child-outputs
              s2 root-occurrence (stack/linked-value [child-occurrence child-occurrence]))
          s4 (stack/enqueue-child-work s3 root-occurrence nil)
          [first-child s5] (stack/take-pending s4)
          [_ s6] (stack/register-occurrence s5 first-child child-occurrence :child-content)
          s7 (stack/connect-child-outputs s6 child-occurrence :compiler-2/list-empty)
          s8 (stack/enqueue-child-work s7 child-occurrence nil)
          [repeated s9] (stack/take-pending s8)
          s10 (stack/connect-reference s9 repeated
                                       (object/as-accessor-network
                                        {:type :stack-ref :identity child-occurrence}))
          root (stack/traversal-result (stack/finish-traversal s10))
          [first-child-value repeated-value] (linked-items (slot root :children))]
      (is (= root-occurrence (slot root :identity)))
      (is (= child-occurrence (slot first-child-value :identity)))
      (is (= :stack-ref (slot repeated-value :type)))
      (is (= child-occurrence (slot repeated-value :identity)))))
  (testing "a back edge to the registered root is an explicit reference"
    (let [s0 (stack/begin-traversal observation root-occurrence)
          [root-work s1] (stack/take-pending s0)
          [_ s2] (stack/register-occurrence s1 root-work root-occurrence :root-content)
          s3 (stack/connect-child-outputs
              s2 root-occurrence (stack/linked-value [root-occurrence]))
          s4 (stack/enqueue-child-work s3 root-occurrence nil)
          [cycle-work s5] (stack/take-pending s4)
          s6 (stack/connect-reference s5 cycle-work
                                      (object/as-accessor-network
                                       {:type :stack-ref :identity root-occurrence}))
          root (stack/traversal-result (stack/finish-traversal s6))
          back-edge (first (linked-items (slot root :children)))]
      (is (= :stack-ref (slot back-edge :type)))
      (is (= root-occurrence (slot back-edge :identity)))
      (is (stack/stack-shape? root))
      (is (stack/reference-integrity? root)))))

(deftest constraints-compare-frozen-content-and-direct-occurrences
  (let [child-graph (graph {})
        source (captured {[:outer :root-call] root-occurrence}
                         {root-occurrence (graph {[:outer :child-call] child-occurrence})
                          child-occurrence child-graph})
        root (object/as-accessor-network
              {:type :stack
               :identity root-occurrence
               :content (capture/scope source root-occurrence)
               :children (stack/linked-value
                          [(object/as-accessor-network
                            {:type :stack
                             :identity child-occurrence
                             :content child-graph
                             :children :compiler-2/list-empty})])})]
    (is (stack/scope-content? source root-occurrence (slot root :content)))
    (is (stack/direct-children? source root-occurrence (slot root :children)))
    (is (false? (stack/direct-children?
                 source root-occurrence :compiler-2/list-empty)))))

(deftest supported-traversal-operators-produce-readable-stack-fields
  (let [source (str "(def-cells struct stack-slot begin-traversal traversal-empty? "
                    "take-pending pending-occurrence discovered-occurrence? "
                    "register-occurrence connect-reference connect-child-outputs "
                    "enqueue-child-work finish-traversal traversal-result)\n"
                    (slurp "examples/lain/top_down/stack/structure.lain") "\n"
                    (slurp "examples/lain/top_down/stack/traversal-state.lain") "\n"
                    "(-> stack:struct struct) (-> stack:stack-slot stack-slot) "
                    "(-> stack:begin-traversal begin-traversal) "
                    "(-> stack:traversal-empty? traversal-empty?) "
                    "(-> stack:take-pending take-pending) "
                    "(-> stack:pending-occurrence pending-occurrence) "
                    "(-> stack:discovered-occurrence? discovered-occurrence?) "
                    "(-> stack:register-occurrence register-occurrence) "
                    "(-> stack:connect-reference connect-reference) "
                    "(-> stack:connect-child-outputs connect-child-outputs) "
                    "(-> stack:enqueue-child-work enqueue-child-work) "
                    "(-> stack:finish-traversal finish-traversal) "
                    "(-> stack:traversal-result traversal-result) "
                    "(def-cells s0 work s1 owned s2 s3 s4) "
                    "(begin-traversal :observation :root s0) "
                    "(take-pending s0 work s1) "
                    "(register-occurrence s1 work :root :content owned s2) "
                    "(connect-child-outputs s2 :root (list) s3) "
                    "(enqueue-child-work s3 :root (list) s4) "
                    "(def-cells finished result result-identity result-content result-children) "
                    "(finish-traversal s4 finished) "
                    "(traversal-result finished result) "
                    "(stack-identity result result-identity) "
                    "(stack-content result result-content) "
                    "(stack-children result result-children)")
        session (:session (editor/prepare source []))]
    (try
      (is (= :root (binding-value session 'result-identity)))
      (is (= :content (binding-value session 'result-content)))
      (is (= :compiler-2/list-empty (binding-value session 'result-children)))
      (finally
        (editor/close-session! session)))))
