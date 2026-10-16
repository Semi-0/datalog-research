(ns propagators.compiler-2-cell-names-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.compiler.cell-names :as names]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.runtime.returned-outputs :as outputs]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as object]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as builder]
            [propagators.runner :as runner]))

(defn completed [compiled]
  (builder/run-propagators (:net compiled) (:props compiled)))

(defn name-at [network id]
  (cell/cell-name (net/network-env-lookup network id)))

(deftest generated-names-describe-cell-roles-and-preserve-values
  (doseq [source ["0" "false" "nil" "\"hello\"" "(+ 2 3)" "(if true 1 2)" "(list 2 3)"]]
    (testing source
      (let [compiled (compiler/compile-source source)
            [tasks replayed] (core/eval-activation-result (select-keys compiled [:effects :messages]) net/empty-net)
            replayed (runner/completed-network (runner/run-network tasks replayed))]
        (let [expected (names/source-name (compiler/parse-source source))]
          (is (= expected (name-at (:net compiled) (:cell compiled)))))
        (is (= (name-at (:net compiled) (:cell compiled))
               (name-at replayed (:cell compiled)))))))
  (let [compiled (compiler/compile-source "(+ 2 2)")
        app (first (application/application-topologies (:net compiled)))
        arguments (:argument-ids app)]
    (is (= 2 (count (set arguments))) "Equal literals remain distinct occurrences")
    (is (= ["CONSTANT" "CONSTANT"] (mapv #(name-at (:net compiled) %) arguments)))
    (is (= 4 (net/network-cell-strongest (completed compiled) (:cell compiled))))))

(deftest explicit-output-reuses-the-binding-without-allocating-a-fallback
  (let [expression (compiler/parse-source "(-> 1 target)")
        compiled (compiler/compile-expr-with-bindings expression (basis/default-bindings) {:seed :named-output})
        network (completed compiled)
        target (env/resolve-binding-id network (:env compiled) 'target)
        fallback (basis/node-id {:seed :named-output :path []} :result)]
    (is (= target (:cell compiled)))
    (is (= 'target (name-at network target)))
    (is (= 1 (net/network-cell-strongest network target)))
    (is (not (contains? (net/net-env (:net compiled)) fallback)))
    (is (not-any? #(= fallback (:id %)) (:effects compiled)))))

(deftest named-cell-metadata-survives-seeding-and-recorded-replay
  (let [id (ids/new-node-id)
        original (declarations/ensure-cell (declarations/begin net/empty-net) id)
        named (-> original (declarations/ensure-cell id "(+ left right)") (declarations/seed-cell id 7))
        recorded (declarations/result named)
        [_ replayed] (core/eval-activation-result recorded net/empty-net)]
    (is (= "(+ left right)" (name-at named id) (name-at replayed id)))
    (is (= 7 (net/network-cell-strongest replayed id)))
    (is (= recorded (declarations/result (declarations/ensure-cell named id "(+ left right)"))))
    (is (= (net/net-graph named) (net/net-graph replayed))))
  (let [id (ids/new-node-id)
        existing (builder/ensure-cell net/empty-net id 'explicit)
        renamed (declarations/ensure-cell (declarations/begin existing) id 'other)]
    (is (= 'explicit (name-at renamed id)))
    (is (= {:effects [] :messages []} (declarations/result renamed)))))

(deftest closure-returns-and-member-ports-have-compiler-owned-names
  (let [compiled (compiler/compile-expr
                  (ast/sequence*
                   (compiler/parse-source
                   "(define stats
                     (network [x y sum product]
                       (-> (+ x y) sum)
                       (-> (* x y) product)
                       (list sum product)))")
                   (compiler/parse-source "(def-cells sum product)")
                   (compiler/parse-source "(stats 3 4 sum product)")))
        network (completed compiled)
        port-names (set (map #(name-at network %) (vals (outputs/outputs network))))]
    (is (= "RESULT" (name-at network (:cell compiled))))
    (is (contains? port-names 'sum))
    (is (contains? port-names 'product))
    (is (not-any? cell/anonymous-name? port-names)))
  (let [compiled (compiler/compile-source "(network [x] (+ x 1))")]
    (is (cell/anonymous-name? (name-at (:net compiled) (:cell compiled))))))

(deftest default-names-are-short-for-literals-nested-and-custom-expressions
  (doseq [v [0 false nil "hello" {:base 7} [1 2]]]
    (is (= "CONSTANT" (names/source-name (ast/lit v)))))
  (let [expression (reduce (fn [body _] (ast/app (ast/sym '+) body 1)) (ast/lit 0) (range 1000))]
    (is (= "RESULT" (names/source-name expression))))
  (is (= "RESULT" (names/source-name (object/compound-object {:ast/type :custom-syntax}))))
  (is (= "RESULT" (names/source-name (ast/app (ast/app 'if 'flag (ast/network ['x] 'x)
                                                                    (ast/network ['x] (ast/app '+ 'x 1))) 'input)))))

(deftest explicit-bindings-and-nested-result-names-remain-short
  (let [compiled (compiler/compile-expr
                  (ast/sequence* (compiler/parse-source "(define input 7)")
                                 (compiler/parse-source "(+ input (* 2 3))")))
        network (completed compiled)
        input (env/resolve-binding-id network (:env compiled) 'input)
        applications (application/application-topologies network)
        generated (map #(name-at network (:result-id %)) applications)]
    (is (= 'input (name-at network input)))
    (is (= "RESULT" (name-at network (:cell compiled))))
    (is (= 13 (net/network-cell-strongest network (:cell compiled))))
    (is (= ["RESULT" "RESULT"] (vec generated)))))
