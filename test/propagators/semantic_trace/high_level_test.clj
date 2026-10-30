(ns propagators.semantic-trace.high-level-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.high-level :as high-level]))

(defn- graph [nodes edges values]
  (trace/graph-union {:nodes nodes :edges edges :values values
                      :node-kinds (zipmap (keys nodes) (repeat :cell))}))

(deftest paths-stop-at-selected-boundaries
  (let [source (graph {:u 'user :slot 'slot :r 'runtime :bridge 'bridge :p 'pi}
                      [[:u :slot] [:slot :r] [:r :bridge] [:bridge :p]]
                      {:u {:assignments :pending} :r {:status :running} :p :ready})
        result (high-level/view source ["user" "runtime" "pi"])]
    (is (= #{:u :r :p} (set (keys (:nodes result)))))
    (is (= #{[:u :r] [:r :p]} (set (:edges result))))
    (is (= (:values source) (:values result)))
    (is (identical? source (:high-level/source result)))
    (is (= :high-level (:dataflow/representation result)))))

(deftest fanout-feedback-and-hidden-cycles
  (let [source (graph {:a 'a :b 'b :c 'c :x 'x :y 'y}
                      [[:a :x] [:x :y] [:y :x] [:y :b] [:y :c] [:b :a]] {})
        result (high-level/view source ["a" "b" "c"])]
    (is (= #{[:a :b] [:a :c] [:b :a]} (set (:edges result))))
    (is (= #{[:a :b] [:b :a]} (set (:bidirectional-edges result)))))
  (testing "feedback through hidden nodes retains a self connection"
    (is (= [[:a :a]] (:edges (high-level/view
                             (graph {:a 'a :x 'x} [[:a :x] [:x :a]] {}) ["a"]))))))

(deftest explicit-identities-and-invalid-selection
  (let [source (graph {:a 'same :b 'same :c 'other} [[:a :c]] {})]
    (is (= #{:a :c} (set (keys (:nodes (high-level/view source [:a :c]))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ambiguous"
                          (high-level/view source ["same"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"absent"
                          (high-level/view source ["missing"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit boundaries"
                          (high-level/view source [])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"semantic graph"
                          (high-level/view {} ["a"])))))

(deftest boundary-values-and-isolated-nodes-survive
  (let [before (graph {:a 'a :x 'slot :b 'b :c 'isolated}
                      [[:a :x] [:x :b]]
                      {:a :pending :x :private :b :contradiction :c :unavailable})
        after (assoc before :values {:a {:task 1} :x :private :b :recovered :c :unavailable})]
    (is (= {:a :pending :b :contradiction :c :unavailable}
           (:values (high-level/view before ["a" "b" "isolated"]))))
    (is (= {:a {:task 1} :b :recovered :c :unavailable}
           (:values (high-level/view after ["a" "b" "isolated"]))))
    (is (= (:nodes (high-level/view before ["a" "b" "isolated"]))
           (:nodes (high-level/view after ["a" "b" "isolated"]))))))

(deftest captured-facts-and-child-scopes-survive
  (let [root (graph {:a 'a :x 'slot :b 'b} [[:a :x] [:x :b]] {:a 1 :b 2})
        child (graph {:private 'private} [] {:private 3})
        source (capture/fragment {:observation :test-observation
                                  :scopes {:root root :child child}
                                  :support #{:premise}
                                  :provenance {:source :fixture}})
        result (high-level/view source ["a" "b"])]
    (is (= (capture/observation-id source) (capture/observation-id result)))
    (is (= (capture/support source) (capture/support result)))
    (is (= (capture/provenance source) (capture/provenance result)))
    (is (= child (capture/scope result :child)))
    (is (= #{:a :b} (set (keys (:nodes (capture/root-graph result))))))))
