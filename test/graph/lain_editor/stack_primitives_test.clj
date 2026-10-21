(ns graph.lain-editor.stack-primitives-test
  (:require [clojure.test :refer [deftest is testing]]
            [graph.lain-editor.runtime :as editor]
            [graph.lain-editor.stack-primitives :as stack]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.datastructures.compound-object :as object]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.capture :as capture]))

(def observation [:observation 1])
(def root-occurrence [observation [:outer :root-call]])
(def child-occurrence [observation [:outer :child-call]])
(def root-node [:outer :root-call])
(def child-node [:outer :child-call])

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
    (is (= root-node (stack/root-compound-node source)))
    (is (= root-occurrence (stack/compound-occurrence source root-node)))
    (is (= root-occurrence (stack/occurrence-identity source root-occurrence)))
    (is (= (trace/graph-union
            (dissoc child-graph :semantic-capture/nested-scopes))
           (stack/content-of-scope source child-occurrence)))
    (let [expansion (first (linked-items
                            (stack/direct-compound-expansions source root-occurrence)))]
      (is (= child-node (object/accessor-source-slot-value expansion :source)))
      (is (= child-occurrence
             (object/accessor-source-slot-value expansion :occurrence))))
    (is (value/contradiction?
         (stack/occurrence-identity source [[:other-observation] [:outer :root-call]])))))

(deftest scope-content-separates-expansion-membership-and-retains-diagnostics
  (let [diagnostic {:kind :missing :message "child is incomplete"}
        scope (assoc (graph {child-node child-occurrence})
                     :semantic-capture/diagnostics [diagnostic])
        source (captured {root-node root-occurrence}
                         {root-occurrence scope})
        content (stack/content-of-scope source root-occurrence)]
    (is (not (contains? content :semantic-capture/nested-scopes)))
    (is (= [diagnostic] (:semantic-capture/diagnostics content)))
    (is (= 1 (count (linked-items
                     (stack/direct-compound-expansions source root-occurrence)))))))

(deftest root-reader-reports-missing-and-ambiguous-membership
  (testing "complete empty root membership is not silently treated as pending success"
    (is (= :missing-root
           (:stack/error
            (first (value/contradiction-provenance
                    (stack/root-compound-node (captured {} {}))))))))
  (testing "multiple roots remain an explicit diagnostic"
    (let [other [observation [:outer :other-call]]
          result (stack/root-compound-node
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

(defn- expansion-request [source occurrence]
  (object/as-accessor-network
   {:type :stack-expansion-request :source source :occurrence occurrence}))

(deftest immutable-traversal-preserves-children-sharing-and-cycles
  (testing "a first occurrence expands and a repeated occurrence becomes a reference"
    (let [s0 (stack/begin-traversal observation root-occurrence)
          root-step (stack/take-pending s0)
          root-work (stack/pending-work root-step)
          s1 (stack/pending-remaining root-step)
          s2 (stack/register-occurrence s1 root-work root-occurrence :root-content)
          s3 (stack/connect-expansion-outputs
              s2 root-occurrence
              (stack/linked-value [(expansion-request :first child-occurrence)
                                   (expansion-request :second child-occurrence)]))
          s4 (stack/enqueue-expansion-work s3 root-occurrence nil)
          child-step (stack/take-pending s4)
          first-child (stack/pending-work child-step)
          s5 (stack/pending-remaining child-step)
          s6 (stack/register-occurrence s5 first-child child-occurrence :child-content)
          s7 (stack/connect-expansion-outputs s6 child-occurrence :compiler-2/list-empty)
          s8 (stack/enqueue-expansion-work s7 child-occurrence nil)
          repeated-step (stack/take-pending s8)
          repeated (stack/pending-work repeated-step)
          s9 (stack/pending-remaining repeated-step)
          s10 (stack/connect-reference s9 repeated child-occurrence)
          root (stack/traversal-result (stack/finish-traversal s10))
          [first-expansion repeated-expansion] (linked-items (slot root :expansions))
          first-child-value (slot first-expansion :target)
          repeated-value (slot repeated-expansion :target)]
      (is (= root-occurrence (slot root :identity)))
      (is (= :first (slot first-expansion :source)))
      (is (= :second (slot repeated-expansion :source)))
      (is (= child-occurrence (slot first-child-value :identity)))
      (is (= :stack-ref (slot repeated-value :type)))
      (is (= child-occurrence (slot repeated-value :identity)))))
  (testing "a back edge to the registered root is an explicit reference"
    (let [s0 (stack/begin-traversal observation root-occurrence)
          root-step (stack/take-pending s0)
          root-work (stack/pending-work root-step)
          s1 (stack/pending-remaining root-step)
          s2 (stack/register-occurrence s1 root-work root-occurrence :root-content)
          s3 (stack/connect-expansion-outputs
              s2 root-occurrence
              (stack/linked-value [(expansion-request :recursive root-occurrence)]))
          s4 (stack/enqueue-expansion-work s3 root-occurrence nil)
          cycle-step (stack/take-pending s4)
          cycle-work (stack/pending-work cycle-step)
          s5 (stack/pending-remaining cycle-step)
          s6 (stack/connect-reference s5 cycle-work root-occurrence)
          root (stack/traversal-result (stack/finish-traversal s6))
          expansion (first (linked-items (slot root :expansions)))
          back-edge (slot expansion :target)]
      (is (= :recursive (slot expansion :source)))
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
               :graph (stack/content-of-scope source root-occurrence)
               :expansions
               (stack/linked-value
                [(object/as-accessor-network
                  {:type :stack-expansion
                   :source child-node
                   :target (object/as-accessor-network
                            {:type :stack
                             :identity child-occurrence
                             :graph (stack/content-of-scope source child-occurrence)
                             :expansions :compiler-2/list-empty})})])})]
    (is (stack/scope-content? source root-occurrence (slot root :graph)))
    (is (stack/direct-expansions? source root-occurrence (slot root :expansions)))
    (is (false? (stack/direct-expansions?
                 source root-occurrence :compiler-2/list-empty)))))

(deftest scope-content-constraint-preserves-localized-conflicts
  (let [left (captured {root-node root-occurrence}
                       {root-occurrence (assoc (graph {}) :values {:leaf 1})})
        right (captured {root-node root-occurrence}
                        {root-occurrence (assoc (graph {}) :values {:leaf 2})})
        conflicting (capture/join left right nil)
        content (stack/content-of-scope conflicting root-occurrence)
        result (stack/scope-content? conflicting root-occurrence content)]
    (is (value/contradiction? result))
    (is (= #{1 2}
           (->> (value/contradiction-provenance result)
                (mapcat :semantic-capture/evidence)
                set)))))

(deftest supported-traversal-operators-produce-readable-stack-fields
  (let [source (str "(def-cells struct stack-slot begin-traversal traversal-empty? "
                    "take-pending pending-work pending-remaining pending-occurrence "
                    "discovered-occurrence? register-occurrence "
                    "connect-reference connect-expansion-outputs "
                    "enqueue-expansion-work finish-traversal traversal-result)\n"
                    (slurp "examples/lain/top_down/stack/structure.lain") "\n"
                    (slurp "examples/lain/top_down/stack/traversal-state.lain") "\n"
                    "(-> stack:struct struct) (-> stack:stack-slot stack-slot) "
                    "(-> stack:begin-traversal begin-traversal) "
                    "(-> stack:traversal-empty? traversal-empty?) "
                    "(-> stack:take-pending take-pending) "
                    "(-> stack:pending-work pending-work) "
                    "(-> stack:pending-remaining pending-remaining) "
                    "(-> stack:pending-occurrence pending-occurrence) "
                    "(-> stack:discovered-occurrence? discovered-occurrence?) "
                    "(-> stack:register-occurrence register-occurrence) "
                    "(-> stack:connect-reference connect-reference) "
                    "(-> stack:connect-expansion-outputs connect-expansion-outputs) "
                    "(-> stack:enqueue-expansion-work enqueue-expansion-work) "
                    "(-> stack:finish-traversal finish-traversal) "
                    "(-> stack:traversal-result traversal-result) "
                    "(define s0 (begin-traversal :observation :root)) "
                    "(define step (take-pending s0)) "
                    "(define work (pending-work step)) "
                    "(define s1 (pending-remaining step)) "
                    "(define s2 (register-occurrence s1 work :root :content)) "
                    "(define s3 (connect-expansion-outputs s2 :root (list))) "
                    "(define s4 (enqueue-expansion-work s3 :root (list))) "
                    "(define finished (finish-traversal s4)) "
                    "(define result (traversal-result finished)) "
                    "(define result-identity (stack-identity result)) "
                    "(define result-graph (stack-graph result)) "
                    "(define result-expansions (stack-expansions result))")
        session (:session (editor/prepare source []))]
    (try
      (is (= :root (binding-value session 'result-identity)))
      (is (= :content (binding-value session 'result-graph)))
      (is (= :compiler-2/list-empty (binding-value session 'result-expansions)))
      (finally
        (editor/close-session! session)))))
