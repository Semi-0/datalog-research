(ns test.propagators.semantic-trace.graph-facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.gur :as gur]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as ttms]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.graph-facts :as facts]
            [propagators.semantic-trace.high-level :as high-level]
            [propagators.semantic-trace.tracer-modifiers :as modifiers]))

(defn graph []
  (facts/from-graph :observation :root
                    {:semantic-trace/graph true
                     :nodes {:u "user" :x "internal" :r "runtime"}
                     :node-kinds {:u :cell :x :propagator :r :cell}
                     :values {:u 1 :r 2}
                     :edges [[:u :x] [:x :r]]} #{}))

(defn permutations [xs]
  (if (empty? xs) [[]]
      (for [x xs rest (permutations (remove #{x} xs))] (cons x rest))))

(deftest modifiers-obey-information-laws
  (let [g (graph)
        ds [(modifiers/declaration :high-level)
            (modifiers/declaration :with-values)
            (modifiers/selection ["user" "runtime"])]
        expected (modifiers/apply-modifiers g ds)]
    (doseq [order (permutations ds)]
      (let [prefixes (reductions #(modifiers/apply-modifiers %1 [%2]) g order)]
        (doseq [[a b] (partition 2 1 prefixes)]
          (is (facts/subsumes? b a)))
        (is (facts/equivalent? expected (last prefixes)))
        (is (= (facts/declarations expected) (facts/declarations (last prefixes))))))
    (is (facts/equivalent? expected (modifiers/apply-modifiers expected ds)))
    (is (= (:nodes g) (:nodes expected)) "Selection retains hidden source facts")
    (is (= (:values g) (:values expected)))
    (is (= #{:u :r} (set (keys (:nodes (high-level/display-graph expected))))))
    (is (= [[:u :r]] (:edges (high-level/display-graph expected))))))

(deftest selector-union-and-captured-identity
  (let [g (graph)
        a (modifiers/apply-modifiers g [(modifiers/selection ["user" "runtime" "user"])])
        b (modifiers/apply-modifiers g [(modifiers/selection ["runtime"])
                                       (modifiers/selection ["user"])])
        capture (capture/fragment {:observation :observation :scopes {:root g}})
        modified (modifiers/apply-modifiers capture [(modifiers/declaration :with-values)])]
    (is (facts/equivalent? a b))
    (is (= (capture/observation-id capture) (capture/observation-id modified)))
    (is (identical? (facts/assertions g)
                    (facts/assertions (capture/root-graph capture))))))

(deftest ttms-revisions-and-independent-evidence
  (let [active (fn [s revision status] {:source (gur/stable-node-id [::test s]) :timestamp revision
                                          :premises-status (if (= :inactive status) :retracted status)})
        p1 (active :p 1 :active)
        q1 (active :q 1 :active)
        g (-> (graph)
              (facts/claim :test/fact [:a] true #{p1} #{p1})
              (facts/claim :test/fact [:a] true #{q1} #{q1}))
        p2 (active :p 2 :inactive)
        withdrawn (facts/claim g :test/fact [:a] value/nothing #{p2} #{p2})
        q2 (active :q 2 :inactive)
        gone (facts/claim withdrawn :test/fact [:a] value/nothing #{q2} #{q2})]
    (is (facts/subsumes? withdrawn g))
    (is (contains? (get (facts/relations withdrawn) :test/fact) [:a]))
    (is (not (contains? (get (facts/relations gone) :test/fact) [:a])))
    (is (= (facts/assertions gone)
           (facts/assertions (facts/claim gone :test/fact [:a] true #{p1} #{p1}))))))

(deftest transformations-require-valid-witnesses
  (let [g (graph) id (facts/assertion-id :observation :root :graph/edge [:u :x])]
    (is (thrown? Exception (facts/derive g {:predicate :derived :tuple [:u] :base true})))
    (is (thrown? Exception (facts/derive g {:predicate :derived :tuple [:u] :base true
                                           :contributors #{[:foreign :root :graph/edge [:u :x]]}})))
    (is (contains? (facts/relations (facts/derive g {:predicate :derived :tuple [:u]
                                                   :base true :contributors #{id}}))
                   :derived))))
