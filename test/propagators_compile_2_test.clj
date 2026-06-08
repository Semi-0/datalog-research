(ns propagators-compile-2-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :refer [default-env]]
            [propagators.compiler-2.main :as main]
            [propagators.compiler-2.parser :as parser]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- run-compiled
  [compiled]
  (nb/run-propagators (:net compiled) (:props compiled)))

(defn- strongest
  [n id]
  (net/network-cell-strongest n id))

(defn- seeded-cell
  [n v]
  (let [id (ids/new-node-id)]
    [id (nb/seed-cell (nb/install-cell n id) id v)]))

(defn- parse
  [source]
  (parser/parse-string source))

(defn- compile-source
  ([source]
   (main/compile-expr (parse source)))
  ([source env opts]
   (main/compile-expr (parse source) env opts)))

(deftest compile-2-compiles-primitive-application
  (testing "application returns a fresh result cell"
    (let [compiled (compile-source "(+ 1 2)")
          result-net (run-compiled compiled)]
      (is (= 3 (strongest result-net (:cell compiled))))
      (is (= (:cell compiled) (main/compiled-result (:net compiled))))
      (is (= (:props compiled) (main/compiled-props (:net compiled)))))))

(defn- propagator-inputs-writing-to
  [n out-id]
  (->> (net/net-graph n)
       (keep (fn [[id node]]
               (when (and (prop/prop? (get (net/net-env n) id))
                          (contains? (:outputs node) out-id))
                 (:inputs node))))))

(deftest compile-2-env-lookup-uses-nearest-scope-source-shadowing
  (testing "a child binding with a nearer scope source is selected from the compound env"
    (let [parent-id (ids/new-node-id)
          child-id (ids/new-node-id)
          env (-> (default-env)
                  (env/bind 'x (env/cell-binding parent-id) 0)
                  env/enter-scope
                  (env/bind 'x (env/cell-binding child-id)))
          binding (env/lookup env 'x)]
      (is (= :cell (:binding/type binding)))
      (is (= child-id (:binding/id binding))))))

(deftest compile-2-network-env-ops-build-scoped-compound-env
  (testing "scope propagators receive parent env one-way and bind locals into a fresh child env"
    (let [parent-x-id (ids/new-node-id)
          local-x-id (ids/new-node-id)
          parent-y-id (ids/new-node-id)
          parent-env-id (ids/new-node-id)
          inherited-env-id (ids/new-node-id)
          local-binding-id (ids/new-node-id)
          scoped-env-id (ids/new-node-id)
          parent-env (env/bind (default-env)
                               'x
                               (env/cell-binding parent-x-id)
                               0)
          parent-env-with-y (env/bind parent-env
                                      'y
                                      (env/cell-binding parent-y-id)
                                      0)
          n0 (-> (nb/install-cells [parent-env-id
                                    inherited-env-id
                                    local-binding-id
                                    scoped-env-id])
                 (nb/seed-cell parent-env-id parent-env)
                 (nb/seed-cell local-binding-id
                               (env/cell-binding local-x-id)))
          [sub-prop n1] ((env/p:sub-env parent-env-id inherited-env-id) n0)
          [bind-prop n2] ((env/p:bind-local
                           'x
                           inherited-env-id
                           local-binding-id
                           scoped-env-id)
                          n1)
          n3 (nb/run-propagators n2 [sub-prop bind-prop])
          inherited-env (strongest n3 inherited-env-id)
          scoped-env (strongest n3 scoped-env-id)
          n4 (nb/seed-cell n3 parent-env-id parent-env-with-y)
          n5 (nb/run-propagators n4
                                 (nb/neighbor-propagator-ids n4 parent-env-id))
          scoped-env-after-parent-update (strongest n5 scoped-env-id)]
      (is (= parent-x-id (:binding/id (env/lookup inherited-env 'x))))
      (is (= local-x-id (:binding/id (env/lookup scoped-env 'x))))
      (is (= local-x-id
             (:binding/id (env/lookup scoped-env-after-parent-update 'x))))
      (is (= parent-y-id
             (:binding/id (env/lookup scoped-env-after-parent-update 'y)))))))

(deftest compile-2-lexical-compound-uses-env-slot-not-hidden-captures
  (testing "compound declarations attach lexical env through slots, not hidden application inputs"
    (let [[bias-id base-net] (seeded-cell net/empty-net 10)
          env (env/bind (default-env) 'bias (env/cell-binding bias-id) 0)
          compiled (compile-source
                    "(let-compound add-bias
                       (compound [x] out
                         (+ x bias))
                       (add-bias 5))"
                    env
                    {:net base-net})
          closure-id (:binding/id (env/lookup (:env compiled) 'add-bias))
          declarations (obj/slot-declarations-for (:net compiled) closure-id)
          apply-inputs (propagator-inputs-writing-to (:net compiled)
                                                     (:cell compiled))
          result-net (run-compiled compiled)]
      (is (contains? declarations main/closure-env-slot))
      (is (not-any? #(contains? % bias-id) apply-inputs))
      (is (= 15 (strongest result-net (:cell compiled)))))))

(deftest compile-2-lexical-argument-shadows-parent-binding
  (testing "input bindings use a nearer scope source than inherited env bindings"
    (let [[outer-x-id base-net] (seeded-cell net/empty-net 100)
          env (env/bind (default-env) 'x (env/cell-binding outer-x-id) 0)
          compiled (compile-source
                    "(let-compound inc-local
                       (compound [x] out
                         (+ x 1))
                       (inc-local 5))"
                    env
                    {:net base-net})
          result-net (run-compiled compiled)]
      (is (= 6 (strongest result-net (:cell compiled)))))))

(deftest compile-2-inner-local-does-not-write-parent-except-output
  (testing "a local cell that shadows a parent symbol stays local unless routed to the compound output"
    (let [[outer-x-id base-net] (seeded-cell net/empty-net 100)
          env (env/bind (default-env) 'x (env/cell-binding outer-x-id) 0)
          compiled (compile-source
                    "(let-compound use-local-x
                       (compound [] out
                         (let-cell [x]
                           (do (<-> 7 x)
                               x)))
                       (use-local-x))"
                    env
                    {:net base-net})
          result-net (run-compiled compiled)]
      (is (= 7 (strongest result-net (:cell compiled))))
      (is (= 100 (strongest result-net outer-x-id))))))

(deftest compile-2-escaped-closure-preserves-lexical-env-through-output
  (testing "a returned closure carries its lexical environment through the declared output"
    (let [compiled (compile-source
                    "(let-compound make-adder
                       (compound [bias] out
                         (compound [x] z
                           (+ x bias)))
                       ((make-adder 10) 5))")
          result-net (run-compiled compiled)]
      (is (= 15 (strongest result-net (:cell compiled)))))))

(deftest compile-2-supports-multiple-nested-compounds-in-one-compound
  (testing "an outer compound can define and apply nested compound propagators"
    (let [compiled (compile-source
                    "(let-compound outer
                       (compound [x] out
                         (let-compound inc
                           (compound [y] z
                             (+ y 1))
                           (let-compound scale-after-inc
                             (compound [y] z
                               (let-compound double
                                 (compound [v] w
                                   (* v 2))
                                 (double (inc y))))
                             (+ (inc x) (scale-after-inc x)))))
                       (outer 4))")
          result-net (run-compiled compiled)]
      (is (= 15 (strongest result-net (:cell compiled)))))))

(deftest compile-2-supports-multiple-compound-declarations-inside-one-compound
  (testing "one compound can declare several local compound propagators and apply them over its arguments"
    (let [compiled (compile-source
                    "(let-compound pipeline
                       (compound [a b] out
                         (let-compound add2
                           (compound [x y] z
                             (+ x y))
                           (let-compound mul2
                             (compound [x y] z
                               (* x y))
                             (let-compound inc
                               (compound [x] z
                                 (+ x 1))
                               (+ (add2 a b)
                                  (mul2 (inc a) b))))))
                       (pipeline 3 4))")
          result-net (run-compiled compiled)]
      (is (= 23 (strongest result-net (:cell compiled)))))))

(deftest compile-2-supports-bi-sync-operator
  (testing "<-> installs bidirectional sync and returns the second cell"
    (let [[a-id n1] (seeded-cell net/empty-net 42)
          b-id (ids/new-node-id)
          n2 (nb/install-cell n1 b-id)
          env (-> (default-env)
                  (env/bind 'a (env/cell-binding a-id) 0)
                  (env/bind 'b (env/cell-binding b-id) 0))
          compiled (compile-source "(<-> a b)" env {:net n2})
          result-net (run-compiled compiled)]
      (is (= b-id (:cell compiled)))
      (is (= 42 (strongest result-net b-id))))))

(deftest compile-2-supports-switch-operator
  (testing "default env includes switch"
    (let [compiled (compile-source "(switch 9 true)")
          result-net (run-compiled compiled)]
      (is (= 9 (strongest result-net (:cell compiled)))))))

(deftest compile-2-propagator-emits-runnable-network-value
  (testing "AST/env cells can produce a compiled network cell"
    (let [[x-id n1] (seeded-cell net/empty-net 4)
          expr-id (ids/new-node-id)
          env-id (ids/new-node-id)
          compiled-id (ids/new-node-id)
          expr (parse "(+ x 1)")
          env (env/bind (default-env) 'x (env/cell-binding x-id) 0)
          n2 (-> n1
                 (nb/install-cell expr-id)
                 (nb/install-cell env-id)
                 (nb/install-cell compiled-id)
                 (nb/seed-cell expr-id expr)
                 (nb/seed-cell env-id env))
          [compile-prop n3] ((main/p:compile-expr expr-id env-id compiled-id) n2)
          outer-net (nb/run-propagators n3 [compile-prop])
          compiled-net (strongest outer-net compiled-id)
          result-net (nb/run-propagators compiled-net
                                         (main/compiled-props compiled-net))]
      (is (= 5 (strongest result-net
                          (main/compiled-result compiled-net)))))))

(deftest compile-2-supports-late-input-partial-evaluation
  (testing "compiled applications can run before inputs exist and produce output later"
    (let [compiled (compile-source "(+ a 2)")
          a-id (:binding/id (env/lookup (:env compiled) 'a))
          n0 (run-compiled compiled)
          n1 (nb/seed-cell n0 a-id 5)
          n2 (nb/run-propagators n1 (nb/neighbor-propagator-ids n1 a-id))]
      (is (= value/nothing (strongest n0 (:cell compiled))))
      (is (= 7 (strongest n2 (:cell compiled)))))))

(deftest compile-2-supports-explicit-output-partial-evaluation
  (testing "app-> wires into a named output cell"
    (let [compiled (compile-source "(app-> + [a 2] out)")
          a-id (:binding/id (env/lookup (:env compiled) 'a))
          out-id (:binding/id (env/lookup (:env compiled) 'out))
          n0 (run-compiled compiled)
          n1 (nb/seed-cell n0 a-id 5)
          n2 (nb/run-propagators n1 (nb/neighbor-propagator-ids n1 a-id))]
      (is (= out-id (:cell compiled)))
      (is (= value/nothing (strongest n0 out-id)))
      (is (= 7 (strongest n2 out-id))))))

(deftest compile-2-supports-late-compound-definition
  (testing "an unresolved operator cell uses the same application propagator when it later receives a closure"
    (let [compiled (compile-source "(app-> some-net [2] out)")
          some-net-id (:binding/id (env/lookup (:env compiled) 'some-net))
          out-id (:binding/id (env/lookup (:env compiled) 'out))
          closure-compiled
          (compile-source
           "(compound [x] out
              (+ x 1))")
          closure-value (strongest (:net closure-compiled)
                                   (:cell closure-compiled))
          n0 (run-compiled compiled)
          n1 (nb/seed-cell n0 some-net-id closure-value)
          n2 (nb/run-propagators n1
                                 (nb/neighbor-propagator-ids n1 some-net-id))]
      (is (= value/nothing (strongest n0 out-id)))
      (is (= 3 (strongest n2 out-id))))))
