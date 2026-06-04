(ns propagators-compile-2-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.compile-2 :as c2]
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
    (let [compiled (c2/compile-expr (c2/app '+ (c2/lit 1) (c2/lit 2)))
          result-net (run-compiled compiled)]
      (is (= 3 (strongest result-net (:cell compiled))))
      (is (= (:cell compiled) (c2/compiled-result (:net compiled))))
      (is (= (:props compiled) (c2/compiled-props (:net compiled)))))))

(deftest compile-2-env-lookup-uses-intensity-shadowing
  (testing "a child binding with higher intensity is selected from the compound env"
    (let [parent-id (ids/new-node-id)
          child-id (ids/new-node-id)
          env (-> (c2/default-env)
                  (c2/bind 'x (c2/cell-binding parent-id) 0)
                  c2/enter-scope
                  (c2/bind 'x (c2/cell-binding child-id)))
          binding (c2/lookup env 'x)]
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
          parent-env (c2/bind (c2/default-env)
                              'x
                              (c2/cell-binding parent-x-id)
                              0)
          parent-env-with-y (c2/bind parent-env
                                     'y
                                     (c2/cell-binding parent-y-id)
                                     0)
          n0 (-> (nb/install-cells [parent-env-id
                                    inherited-env-id
                                    local-binding-id
                                    scoped-env-id])
                 (nb/seed-cell parent-env-id parent-env)
                 (nb/seed-cell local-binding-id
                               (c2/cell-binding local-x-id)))
          [sub-prop n1] ((c2/p:sub-env parent-env-id inherited-env-id) n0)
          [bind-prop n2] ((c2/p:bind-local
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
      (is (= parent-x-id (:binding/id (c2/lookup inherited-env 'x))))
      (is (= local-x-id (:binding/id (c2/lookup scoped-env 'x))))
      (is (= local-x-id
             (:binding/id (c2/lookup scoped-env-after-parent-update 'x))))
      (is (= parent-y-id
             (:binding/id (c2/lookup scoped-env-after-parent-update 'y)))))))

(deftest compile-2-lexical-compound-captures-parent-cell
  (testing "compound declarations capture parent cells as hidden inputs"
    (let [[bias-id base-net] (seeded-cell net/empty-net 10)
          env (c2/bind (c2/default-env) 'bias (c2/cell-binding bias-id) 0)
          expr (c2/let-compound
                'add-bias
                (c2/compound {:inputs ['x] :output 'out}
                  (c2/app '+ 'x 'bias))
                (c2/app 'add-bias (c2/lit 5)))
          compiled (c2/compile-expr expr env {:net base-net})
          result-net (run-compiled compiled)]
      (is (= 15 (strongest result-net (:cell compiled)))))))

(deftest compile-2-lexical-argument-shadows-parent-binding
  (testing "input bindings are higher intensity than inherited env bindings"
    (let [[outer-x-id base-net] (seeded-cell net/empty-net 100)
          env (c2/bind (c2/default-env) 'x (c2/cell-binding outer-x-id) 0)
          expr (c2/let-compound
                'inc-local
                (c2/compound {:inputs ['x] :output 'out}
                  (c2/app '+ 'x (c2/lit 1)))
                (c2/app 'inc-local (c2/lit 5)))
          compiled (c2/compile-expr expr env {:net base-net})
          result-net (run-compiled compiled)]
      (is (= 6 (strongest result-net (:cell compiled)))))))

(deftest compile-2-supports-multiple-nested-compounds-in-one-compound
  (testing "an outer compound can define and apply nested compound propagators"
    (let [expr
          (c2/let-compound
           'outer
           (c2/compound {:inputs ['x] :output 'out}
             (c2/let-compound
              'inc
              (c2/compound {:inputs ['y] :output 'z}
                (c2/app '+ 'y (c2/lit 1)))
              (c2/let-compound
               'scale-after-inc
               (c2/compound {:inputs ['y] :output 'z}
                 (c2/let-compound
                  'double
                  (c2/compound {:inputs ['v] :output 'w}
                    (c2/app '* 'v (c2/lit 2)))
                  (c2/app 'double (c2/app 'inc 'y))))
               (c2/app '+ (c2/app 'inc 'x)
                       (c2/app 'scale-after-inc 'x)))))
           (c2/app 'outer (c2/lit 4)))
          compiled (c2/compile-expr expr)
          result-net (run-compiled compiled)]
      (is (= 15 (strongest result-net (:cell compiled)))))))

(deftest compile-2-supports-bi-sync-operator
  (testing "<-> installs bidirectional sync and returns the second cell"
    (let [[a-id n1] (seeded-cell net/empty-net 42)
          b-id (ids/new-node-id)
          n2 (nb/install-cell n1 b-id)
          env (-> (c2/default-env)
                  (c2/bind 'a (c2/cell-binding a-id) 0)
                  (c2/bind 'b (c2/cell-binding b-id) 0))
          compiled (c2/compile-expr (c2/app '<-> 'a 'b) env {:net n2})
          result-net (run-compiled compiled)]
      (is (= b-id (:cell compiled)))
      (is (= 42 (strongest result-net b-id))))))

(deftest compile-2-supports-switch-operator
  (testing "default env includes switch"
    (let [compiled (c2/compile-expr (c2/app 'switch
                                            (c2/lit 9)
                                            (c2/lit true)))
          result-net (run-compiled compiled)]
      (is (= 9 (strongest result-net (:cell compiled)))))))

(deftest compile-2-propagator-emits-runnable-network-value
  (testing "AST/env cells can produce a compiled network cell"
    (let [[x-id n1] (seeded-cell net/empty-net 4)
          expr-id (ids/new-node-id)
          env-id (ids/new-node-id)
          compiled-id (ids/new-node-id)
          expr (c2/app '+ 'x (c2/lit 1))
          env (c2/bind (c2/default-env) 'x (c2/cell-binding x-id) 0)
          n2 (-> n1
                 (nb/install-cell expr-id)
                 (nb/install-cell env-id)
                 (nb/install-cell compiled-id)
                 (nb/seed-cell expr-id expr)
                 (nb/seed-cell env-id env))
          [compile-prop n3] ((c2/p:compile-expr expr-id env-id compiled-id) n2)
          outer-net (nb/run-propagators n3 [compile-prop])
          compiled-net (strongest outer-net compiled-id)
          result-net (nb/run-propagators compiled-net
                                         (c2/compiled-props compiled-net))]
      (is (= 5 (strongest result-net
                          (c2/compiled-result compiled-net)))))))

(deftest compile-2-supports-late-input-partial-evaluation
  (testing "compiled applications can run before inputs exist and produce output later"
    (let [compiled (c2/compile-expr (c2/app '+ 'a (c2/lit 2)))
          a-id (:binding/id (c2/lookup (:env compiled) 'a))
          n0 (run-compiled compiled)
          n1 (nb/seed-cell n0 a-id 5)
          n2 (nb/run-propagators n1 (nb/neighbor-propagator-ids n1 a-id))]
      (is (= value/nothing (strongest n0 (:cell compiled))))
      (is (= 7 (strongest n2 (:cell compiled)))))))

(deftest compile-2-supports-explicit-output-partial-evaluation
  (testing "app-> wires into a named output cell"
    (let [compiled (c2/compile-expr
                    (c2/app-> '+ ['a (c2/lit 2)] 'out))
          a-id (:binding/id (c2/lookup (:env compiled) 'a))
          out-id (:binding/id (c2/lookup (:env compiled) 'out))
          n0 (run-compiled compiled)
          n1 (nb/seed-cell n0 a-id 5)
          n2 (nb/run-propagators n1 (nb/neighbor-propagator-ids n1 a-id))]
      (is (= out-id (:cell compiled)))
      (is (= value/nothing (strongest n0 out-id)))
      (is (= 7 (strongest n2 out-id))))))

(deftest compile-2-supports-late-compound-definition
  (testing "an unresolved operator compiles as a deferred closure cell"
    (let [compiled (c2/compile-expr (c2/app-> 'some-net [(c2/lit 2)] 'out))
          some-net-id (:binding/id (c2/lookup (:env compiled) 'some-net))
          out-id (:binding/id (c2/lookup (:env compiled) 'out))
          closure-compiled
          (c2/compile-expr
           (c2/compound {:inputs ['x] :output 'out}
             (c2/app '+ 'x (c2/lit 1))))
          closure-value (strongest (:net closure-compiled)
                                   (:cell closure-compiled))
          n0 (run-compiled compiled)
          n1 (nb/seed-cell n0 some-net-id closure-value)
          n2 (nb/run-propagators n1
                                 (nb/neighbor-propagator-ids n1 some-net-id))]
      (is (= value/nothing (strongest n0 out-id)))
      (is (= 3 (strongest n2 out-id))))))
