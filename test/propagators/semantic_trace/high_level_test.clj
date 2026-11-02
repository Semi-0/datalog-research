(ns propagators.semantic-trace.high-level-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.semantic-trace :as trace]
            [propagators.gur :as gur]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.graph-facts :as facts]
            [propagators.semantic-trace.high-level :as high-level]))

(defn- graph [nodes edges values]
  (trace/graph-union {:nodes nodes :edges edges :values values
                      :node-kinds (zipmap (keys nodes) (repeat :cell))}))

(defn- displayed [source selectors]
  (high-level/display-graph (capture/root-graph (high-level/view source selectors))))

(deftest paths-stop-at-selected-boundaries
  (let [source (graph {:u 'user :slot 'slot :r 'runtime :bridge 'bridge :p 'pi}
                      [[:u :slot] [:slot :r] [:r :bridge] [:bridge :p]]
                      {:u {:assignments :pending} :r {:status :running} :p :ready})
        result (displayed source ["user" "runtime" "pi"])]
    (is (= #{:u :r :p} (set (keys (:nodes result)))))
    (is (= #{[:u :r] [:r :p]} (set (:edges result))))
    (is (= (:values source) (:values result)))
    (is (= (:nodes source) (:nodes (:high-level/source result))))
    (is (= :high-level (:dataflow/representation result)))
    (is (= (:nodes source) (:nodes (high-level/view source ["user" "runtime" "pi"])))
        "The declaration retains hidden source nodes")))

(deftest fanout-feedback-and-hidden-cycles
  (let [source (graph {:a 'a :b 'b :c 'c :x 'x :y 'y}
                      [[:a :x] [:x :y] [:y :x] [:y :b] [:y :c] [:b :a]] {})
        result (displayed source ["a" "b" "c"])]
    (is (= #{[:a :b] [:a :c] [:b :a]} (set (:edges result))))
    (is (= #{[:a :b] [:b :a]} (set (:bidirectional-edges result)))))
  (testing "feedback through hidden nodes retains a self connection"
    (is (= [[:a :a]] (:edges (displayed
                             (graph {:a 'a :x 'x} [[:a :x] [:x :a]] {}) ["a"]))))))

(deftest explicit-identities-and-invalid-selection
  (let [source (graph {:a 'same :b 'same :c 'other} [[:a :c]] {})]
    (is (= #{:a :c} (set (keys (:nodes (displayed source [:a :c]))))))
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
    (is (= (:values before) (:values (displayed before ["a" "b" "isolated"]))))
    (is (= (:values after) (:values (displayed after ["a" "b" "isolated"]))))
    (is (not (contains? (:nodes (displayed before ["a" "b" "isolated"])) :x)))
    (is (= (:nodes (displayed before ["a" "b" "isolated"]))
           (:nodes (displayed after ["a" "b" "isolated"]))))))

(deftest captured-facts-and-child-scopes-survive
  (let [root (graph {:a 'a :x 'slot :b 'b} [[:a :x] [:x :b]] {:a 1 :b 2})
        child (graph {:private 'private} [] {:private 3})
        premise {:source (gur/stable-node-id [::fixture]) :timestamp 1 :premises-status :active}
        source (capture/fragment {:observation :test-observation
                                  :scopes {:root root :child child}
                                  :support #{premise}
                                  :provenance [{:source :fixture}]})
        result (high-level/view source ["a" "b"])
        retained (set (keys (:nodes (capture/root-graph result))))
        visible (set (keys (:nodes (high-level/display-graph (capture/root-graph result)))))]
    (is (= (capture/observation-id source) (capture/observation-id result)))
    (is (= (capture/support source) (capture/support result)))
    (is (= (capture/provenance source) (capture/provenance result)))
    (is (= child (facts/source-projection (capture/scope result :child))))
    (is (= #{:a :x :b} retained))
    (is (= #{:a :b} visible))))
