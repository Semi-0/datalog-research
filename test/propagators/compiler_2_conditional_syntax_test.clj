(ns propagators.compiler-2-conditional-syntax-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.live-environment.source :as file-source]
            [graph.json :as json]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.compiler.declarations :as declarations]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.language.contract :as contract]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.operators.call-graph-syntax :as call-syntax]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.inspection.semantic-support :as inspection]
            [propagators.compiler-2.runtime.session.instance-replay :as replay]
            [propagators.compiler-2.runtime.tui.block-compiler :as block-compiler]
            [propagators.compiler-2.operators.block-premise :as premise]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.network-builder :as builder]))

(deftest conditional-constructor-normalizes-all-three-children
  (let [expr (ast/if* 'flag 1 (ast/lit 2))]
    (is (= :if (ast/type expr)))
    (is (= 'flag (ast/name (ast/condition expr))))
    (is (= 1 (ast/value (ast/then-expression expr))))
    (is (= 2 (ast/value (ast/else-expression expr))))
    (is (= [(ast/condition expr) (ast/then-expression expr) (ast/else-expression expr)]
           (contract/children expr)))
    (is (= "(if ...)" (inspection/ast-label expr)))))

(deftest parser-lowers-cond-to-nested-core-conditionals
  (let [expr (parser/parse-string "(cond [a 1 b 2 else 3])")
        nested (ast/else-expression expr)]
    (is (= :if (ast/type expr)))
    (is (= 'a (ast/name (ast/condition expr))))
    (is (= 1 (ast/value (ast/then-expression expr))))
    (is (= :if (ast/type nested)))
    (is (= 'b (ast/name (ast/condition nested))))
    (is (= 2 (ast/value (ast/then-expression nested))))
    (is (= 3 (ast/value (ast/else-expression nested)))))
  (is (= value/nothing
         (ast/value (ast/else-expression (parser/parse-string "(cond [false 1])")))))
  (is (= 42 (ast/value (parser/parse-string "(cond [else 42])")))))

(deftest malformed-conditionals-retain-parser-errors
  (doseq [[source pattern]
          [["(if true 1)" #"if expects"]
           ["(if true 1 2 3)" #"if expects"]
           ["(cond (true 1))" #"cond expects one vector"]
           ["(cond [true])" #"condition/expression pairs"]
           ["(cond [])" #"at least one clause"]
           ["(cond [else 1 true 2])" #"else clause must be last"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo pattern
                          (parser/parse-string source)) source)))

(deftest declaration-identity-observes-every-conditional-child
  (let [base (ast/if* true 1 2)
        variants [(ast/if* false 1 2) (ast/if* true 3 2) (ast/if* true 1 3)]
        keys (mapv contract/declaration-key (into [base] variants))]
    (is (= 4 (count (set keys))))
    (is (= (contract/declaration-key base)
           (contract/declaration-key (parser/parse-string "(if true 1 2)"))))))

(deftest conditional-dispatch-precedes-application
  (let [expr (ast/if* true 1 2)
        lowered
        (with-redefs [declarations/declare-application-context
                      (fn [& _] (throw (ex-info "Conditional dispatched as application" {})))]
          (trampoline compiler/compiler-dispatch
                      (fn [_state expression _k] expression)
                      {:path []} expr identity))]
    (is (= :apply (ast/type lowered)))
    (is (= :apply (ast/type (ast/operator lowered))))
    (is (= [:literal :network :network]
           (mapv ast/type (ast/args (ast/operator lowered)))))))

(deftest conditional-syntax-is-independent-of-if-bindings
  (is (not-any? #(= 'if (first %)) (basis/default-bindings)))
  (doseq [bindings [(basis/default-bindings)
                    (conj (basis/default-bindings) ['if 99])]
          flag [true false]]
    (let [compiled (compiler/compile-expr-with-bindings (ast/if* flag 1 2) bindings)
          network (builder/run-propagators (:net compiled) (:props compiled))]
      (is (= (if flag 1 2) (data/payload
                            (net/network-cell-strongest network (:cell compiled)))))))
  (let [compiled (compiler/compile-expr-with-bindings (ast/sym 'if) [['if 99]])]
    (is (= 99 (net/network-cell-strongest (:net compiled) (:cell compiled))))))

(deftest conditional-traversal-finds-calls-and-free-names
  (let [expr (parser/parse-string "(if (predicate flag) (left x) (right y))")
        sites (call-syntax/call-sites expr #{'left})]
    (is (= ["predicate" "left" "right"] (mapv :operator-label sites)))
    (is (= [[0] [1] [2]] (mapv :path sites)))
    (is (= [false true false] (mapv :bound? sites)))
    (is (= '#{predicate flag left x right y} (file-source/free-names expr #{})))))

(deftest versioned-rewrite-wraps-result-and-visits-conditional-children
  (let [context (premise/premise-context :conditional 0)
        rewritten (block-compiler/rewrite-expr
                   context (parser/parse-string "(if (p 1) (f 2) (g 3))"))
        conditional (first (ast/args rewritten))]
    (is (block-compiler/dependency-term? rewritten))
    (is (= :if (ast/type conditional)))
    (is (every? block-compiler/dependency-term? (contract/children conditional)))
    (is (= rewritten (block-compiler/rewrite-expr context rewritten)))))

(deftest conditional-json-replay-preserves-source-and-premise-retraction
  (let [sources ["(if true 11 (missing 0))" "(cond [false (missing 0) else 12])"]
        manifest {:schema replay/schema :schema-version replay/schema-version
                  :clients [{:client-id "A" :mode :versioned-premise}]
                  :commits (mapv (fn [index source]
                                   {:commit-id (str "00000000-0000-0000-0000-" (format "%012d" (inc index)))
                                    :client-id "A" :index 0
                                    :expected-version (when (pos? index) (dec index))
                                    :text source})
                                 (range) sources)}
        session (runtime/new-session)
        imported (runtime/import-instance! session (json/read-json (json/write-json manifest)))
        exported (runtime/export-instance @session)
        restored (runtime/new-session)]
    (is (= :imported (:status imported)))
    (is (= sources (mapv :text (:commits exported))))
    (is (= [11 12] (mapv :compiled-result (:steps imported))))
    (runtime/import-instance! restored (json/read-json (json/write-json exported)))
    (is (= 12 (get-in (runtime/read-tui-view @restored {:client-id "A"}) [:blocks 1 :value])))
    (let [record (get-in @restored [:tuis "A" :blocks 0 :version-history 1])]
      (runtime/commit-runtime-input!
       restored {:runtime/input :cell-message :cell-id (:premise-state-cell record)
                 :update (tms/distributed-premise-update (:premise-id record) 10 false)})
      (is (= value/nothing
             (get-in (runtime/read-tui-view @restored {:client-id "A"}) [:blocks 1 :value]))))))
