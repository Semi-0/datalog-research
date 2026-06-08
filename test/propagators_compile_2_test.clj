(ns propagators-compile-2-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.ast :as ast]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :refer [default-env]]
            [propagators.compiler-2.main :as main]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

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

(deftest compile-2-compiles-primitive-application
  (testing "application returns a fresh result cell"
    (let [compiled (main/compile-expr (ast/app '+ (ast/lit 1) (ast/lit 2)))
          result-net (run-compiled compiled)]
      (is (= 3 (strongest result-net (:cell compiled))))
      (is (= (:cell compiled) (main/compiled-result (:net compiled))))
      (is (= (:props compiled) (main/compiled-props (:net compiled)))))))

(deftest compile-2-env-lookup-uses-intensity-shadowing
  (testing "a child binding with higher intensity is selected from the compound env"
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

(deftest compile-2-lexical-compound-captures-parent-cell
  (testing "compound declarations capture parent cells as hidden inputs"
    (let [[bias-id base-net] (seeded-cell net/empty-net 10)
          env (env/bind (default-env) 'bias (env/cell-binding bias-id) 0)
          expr (ast/let-compound
                'add-bias
                (ast/compound {:inputs ['x] :output 'out}
                  (ast/app '+ 'x 'bias))
                (ast/app 'add-bias (ast/lit 5)))
          compiled (main/compile-expr expr env {:net base-net})
          result-net (run-compiled compiled)]
      (is (= 15 (strongest result-net (:cell compiled)))))))

(deftest compile-2-lexical-argument-shadows-parent-binding
  (testing "input bindings are higher intensity than inherited env bindings"
    (let [[outer-x-id base-net] (seeded-cell net/empty-net 100)
          env (env/bind (default-env) 'x (env/cell-binding outer-x-id) 0)
          expr (ast/let-compound
                'inc-local
                (ast/compound {:inputs ['x] :output 'out}
                  (ast/app '+ 'x (ast/lit 1)))
                (ast/app 'inc-local (ast/lit 5)))
          compiled (main/compile-expr expr env {:net base-net})
          result-net (run-compiled compiled)]
      (is (= 6 (strongest result-net (:cell compiled)))))))

(deftest compile-2-supports-multiple-nested-compounds-in-one-compound
  (testing "an outer compound can define and apply nested compound propagators"
    (let [expr
          (ast/let-compound
           'outer
           (ast/compound {:inputs ['x] :output 'out}
             (ast/let-compound
              'inc
              (ast/compound {:inputs ['y] :output 'z}
                (ast/app '+ 'y (ast/lit 1)))
              (ast/let-compound
               'scale-after-inc
               (ast/compound {:inputs ['y] :output 'z}
                 (ast/let-compound
                  'double
                  (ast/compound {:inputs ['v] :output 'w}
                    (ast/app '* 'v (ast/lit 2)))
                  (ast/app 'double (ast/app 'inc 'y))))
               (ast/app '+ (ast/app 'inc 'x)
                       (ast/app 'scale-after-inc 'x)))))
           (ast/app 'outer (ast/lit 4)))
          compiled (main/compile-expr expr)
          result-net (run-compiled compiled)]
      (is (= 15 (strongest result-net (:cell compiled)))))))

(deftest compile-2-supports-bi-sync-operator
  (testing "<-> installs bidirectional sync and returns the second cell"
    (let [[a-id n1] (seeded-cell net/empty-net 42)
          b-id (ids/new-node-id)
          n2 (nb/install-cell n1 b-id)
          env (-> (default-env)
                  (env/bind 'a (env/cell-binding a-id) 0)
                  (env/bind 'b (env/cell-binding b-id) 0))
          compiled (main/compile-expr (ast/app '<-> 'a 'b) env {:net n2})
          result-net (run-compiled compiled)]
      (is (= b-id (:cell compiled)))
      (is (= 42 (strongest result-net b-id))))))

(deftest compile-2-supports-switch-operator
  (testing "default env includes switch"
    (let [compiled (main/compile-expr (ast/app 'switch
                                               (ast/lit 9)
                                               (ast/lit true)))
          result-net (run-compiled compiled)]
      (is (= 9 (strongest result-net (:cell compiled)))))))

(deftest compile-2-propagator-emits-runnable-network-value
  (testing "AST/env cells can produce a compiled network cell"
    (let [[x-id n1] (seeded-cell net/empty-net 4)
          expr-id (ids/new-node-id)
          env-id (ids/new-node-id)
          compiled-id (ids/new-node-id)
          expr (ast/app '+ 'x (ast/lit 1))
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
    (let [compiled (main/compile-expr (ast/app '+ 'a (ast/lit 2)))
          a-id (:binding/id (env/lookup (:env compiled) 'a))
          n0 (run-compiled compiled)
          n1 (nb/seed-cell n0 a-id 5)
          n2 (nb/run-propagators n1 (nb/neighbor-propagator-ids n1 a-id))]
      (is (= value/nothing (strongest n0 (:cell compiled))))
      (is (= 7 (strongest n2 (:cell compiled)))))))

(deftest compile-2-supports-explicit-output-partial-evaluation
  (testing "app-> wires into a named output cell"
    (let [compiled (main/compile-expr
                    (ast/app-> '+ ['a (ast/lit 2)] 'out))
          a-id (:binding/id (env/lookup (:env compiled) 'a))
          out-id (:binding/id (env/lookup (:env compiled) 'out))
          n0 (run-compiled compiled)
          n1 (nb/seed-cell n0 a-id 5)
          n2 (nb/run-propagators n1 (nb/neighbor-propagator-ids n1 a-id))]
      (is (= out-id (:cell compiled)))
      (is (= value/nothing (strongest n0 out-id)))
      (is (= 7 (strongest n2 out-id))))))

(deftest compile-2-supports-late-compound-definition
  (testing "an unresolved operator compiles as a deferred closure cell"
    (let [compiled (main/compile-expr (ast/app-> 'some-net [(ast/lit 2)] 'out))
          some-net-id (:binding/id (env/lookup (:env compiled) 'some-net))
          out-id (:binding/id (env/lookup (:env compiled) 'out))
          closure-compiled
          (main/compile-expr
           (ast/compound {:inputs ['x] :output 'out}
             (ast/app '+ 'x (ast/lit 1))))
          closure-value (strongest (:net closure-compiled)
                                   (:cell closure-compiled))
          n0 (run-compiled compiled)
          n1 (nb/seed-cell n0 some-net-id closure-value)
          n2 (nb/run-propagators n1
                                 (nb/neighbor-propagator-ids n1 some-net-id))]
      (is (= value/nothing (strongest n0 out-id)))
      (is (= 3 (strongest n2 out-id))))))
