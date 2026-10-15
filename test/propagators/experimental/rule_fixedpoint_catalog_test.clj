(ns propagators.experimental.rule-fixedpoint-catalog-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [examples.lain.amb.solver-strategy :as strategy]
            [examples.lain.amb.worker-driver :as driver]
            [examples.lain.rule-fixedpoint.catalog :as catalog]
            [examples.lain.rule-fixedpoint.engine :as engine]
            [examples.lain.rule-fixedpoint.fixtures :as fixtures]
            [examples.lain.rule-fixedpoint.model :as model]
            [examples.lain.rule-fixedpoint.query :as query]
            [examples.lain.rule-fixedpoint.solvers :as solvers]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as session]
            [propagators.cells.value :as value]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.network :as net]))

(defn dataflow-source []
  (slurp (io/resource "examples/lain/rule_fixedpoint/dataflow.lain")))

(defn completed [world]
  (let [result (engine/run world)]
    (if (= :completed (:status result)) result (throw (:error result)))))

(deftest complete-dataflow-catalog-comes-from-lain
  (let [generic (extension/extension-bundle
                 {:id (extension/extension-id catalog/session-extension) :effects []
                  :bindings (filterv #(contains? #{'relation 'rule 'rule-set} (first %))
                                     (extension/extension-bindings catalog/session-extension))})]
    (with-redefs [catalog/session-extension generic]
      (let [rules (:rules (catalog/compile-source (dataflow-source)))]
        (is (= [{:id :flow-to-reach :head [:reach [:x :y]] :body [[:flow [:x :y]]] :guard nil}
                {:id :reach-via-reach-flow :head [:reach [:x :z]]
                 :body [[:reach [:x :y]] [:flow [:y :z]]] :guard nil}
                {:id :taint-via-taint-flow :head [:taint [:x :z]]
                 :body [[:taint [:x :y]] [:flow [:y :z]]] :guard nil}] rules))
        (is (every? #(nil? (:guard %)) rules))))))

(deftest generic-declarations-reject-unsupported-rule-shapes
  (is (thrown? clojure.lang.ExceptionInfo (model/relation :p :x 1)))
  (is (thrown? clojure.lang.ExceptionInfo
               (model/rule :empty (model/relation :p :x :y))))
  (is (thrown? clojure.lang.ExceptionInfo
               (model/rule :unsafe (model/relation :p :x :z) (model/relation :p :x :y))))
  (is (thrown? clojure.lang.ExceptionInfo
               (model/rule :too-many (model/relation :p :x :y)
                           (model/relation :p :x :y) (model/relation :p :x :y)
                           (model/relation :p :x :y)))))

(deftest declaration-bindings-are-session-local-and-idempotent
  (let [fresh (session/empty-state)
        installed (-> fresh
                      (extension/install-session-extension
                       ttms/session-extension {})
                      (extension/install-session-extension catalog/session-extension {}))]
    (doseq [symbol ['relation 'rule 'rule-set 'guarded-rule]]
      (is (nil? (env/resolve-binding (:program/net fresh) (:program/env fresh) symbol)))
      (is (some? (env/resolve-binding (:program/net installed) (:program/env installed) symbol))))
    (is (= installed (extension/install-session-extension installed catalog/session-extension {})))
    (is (empty? (extension/extension-effects catalog/session-extension)))
    (is (empty? (:session/extensions (session/empty-state))))))

(deftest unconditional-compiled-questions-do-not-invent-a-guard
  (let [rules (:rules (catalog/compile-source (dataflow-source)))]
    (doseq [rule rules]
      (let [template (query/compile-template rule {})]
        (is (not (string/includes? (:source template) "rule-guard")))
        (is (string/includes? (:source template) "rule-match?"))))))

(deftest meaningful-guard-control-is-also-declared-in-a-lain-file
  (let [declared (catalog/compile-source
                 (slurp (io/resource "examples/lain/rule_fixedpoint/selective_guard.lain")))
        rule (first (:rules declared))
        sources {:a {:predicate :left :tuple [1 2]}
                 :b {:predicate :right :tuple [2 3]}
                 :c {:predicate :right :tuple [2 4]}}
        options (solvers/attach (:rules declared) {:strategy :indexed-delta :domain [1 2 3 4]} declared)
        result (completed (engine/seed (engine/prepare (:rules declared) options sources)))]
    (is (= [:answer [:x :z]] (:head rule)))
    (is (= [[:left [:x :y]] [:right [:y :z]]] (:body rule)))
    (is (some? (:guard rule)))
    (is (= #{[1 3]} (engine/facts result :answer)))))

(deftest lain-rule-edit-changes-inference-without-a-host-rule-change
  (let [source (dataflow-source)
        without-recursion (string/replace source
                           "(relation :reach :x :y)\n    (relation :flow :y :z)"
                           "(relation :flow :x :y)\n    (relation :flow :y :z)")
        sources (fixtures/source-specs :flow [[0 1] [1 2] [2 3]])
        run (fn [source]
              (let [declared (catalog/compile-source source)]
                (completed (engine/seed (engine/prepare (:rules declared) {:strategy :indexed-delta} sources)))))
        recursive (run source) two-hop (run without-recursion)]
    (is (contains? (engine/facts recursive :reach) [0 3]))
    (is (not (contains? (engine/facts two-hop :reach) [0 3])))
    (is (contains? (engine/facts two-hop :reach) [0 2]))))

(deftest real-dataflow-uses-only-the-extension-and-unconditional-join
  (let [declared (catalog/compile-source (dataflow-source))
        fixture (fixtures/dataflow)
        built (engine/prepare (:rules declared) {:strategy :indexed-delta :domain (:domain fixture)} (:sources fixture))
        active (with-redefs [query/compile-template (fn [& _] (throw (ex-info "Unneeded query" {})))
                             query/instantiate (fn [& _] (throw (ex-info "Unneeded validator" {})))]
                 (completed (engine/seed built)))
        tuple (get-in fixture [:sources :flow-0 :tuple])
        withdrawn (completed (engine/inject active :flow-0 value/nothing {:timestamp 2 :status :retracted}))
        restored (completed (engine/inject withdrawn :flow-0 tuple {:timestamp 3 :status :active}))]
    (is (= 36 (count (engine/facts active :reach))))
    (is (= 9 (count (engine/facts active :taint))))
    (doseq [[predicate expected] (:expected fixture)]
      (is (= expected (engine/facts active predicate)))
      (is (= expected (engine/facts restored predicate))))
    (is (< (count (engine/facts withdrawn :reach)) 36))
    (is (= (count (net/net-env (:network built))) (count (net/net-env (:network restored)))))))

(deftest declared-dataflow-rules-swap-strategies-without-a-rewrite
  (let [declared (catalog/compile-source (dataflow-source))
        sources {:a {:predicate :flow :tuple [0 1]} :b {:predicate :flow :tuple [1 2]}
                 :root {:predicate :taint :tuple [0 0]}}
        options (solvers/attach (:rules declared) {:strategy :indexed-delta :domain [0 1 2]} declared)
        initial (completed (engine/seed (engine/prepare (:rules declared) options sources)))
        relation-content (fn [world]
                           (mapv #(net/network-cell-content (:network world) %)
                                 (vals (get-in world [:cells :relations]))))
        pool (driver/pool 4)]
    (try
      (binding [strategy/*executor* pool]
        (doseq [mode solvers/modes]
          (let [world (completed (engine/select-strategy initial mode))]
            (is (= #{[0 1] [1 2] [0 2]} (engine/facts world :reach)) (str mode))
            (is (= #{[0 0] [0 1] [0 2]} (engine/facts world :taint)) (str mode))
            (is (= (relation-content initial) (relation-content world)) (str mode)))))
      (finally (driver/close! pool)))))
