(ns propagators-generic-procedure-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.closure :as closure]
            [propagators.compile :as compile]
            [propagators.generic-procedure :as generic]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- installed-cells
  [& ids]
  (reduce nb/install-cell net/empty-net ids))

(defn- seed-closure-cell
  [n id closure-value]
  (nb/install-cell n id closure-value closure-value))

(defn- run-props
  [n prop-ids]
  (nb/run-propagators n prop-ids))

(defn- initialize-generic
  [n generic-id default-id]
  (let [[init-props n'] ((generic/make-generic-propagator generic-id default-id) n)]
    (run-props n' init-props)))

(defn- define-method
  [n generic-id method-key predicate-ids matcher-id handler-id]
  (let [predicates (if (sequential? predicate-ids) predicate-ids [predicate-ids])
        [define-prop n'] ((generic/define-generic-propagator generic-id
                                                             method-key
                                                             predicates
                                                             matcher-id
                                                             handler-id)
                          n)]
    (run-props n' [define-prop])))

(defn- apply-generic
  [n generic-id arg-ids out-id]
  (let [[apply-prop n'] ((generic/p:apply-generic generic-id arg-ids out-id) n)]
    (run-props n' [apply-prop])))

(defn- one-arg-generic-net
  [arg-value]
  (let [generic-id (ids/new-node-id)
        arg-id (ids/new-node-id)
        default-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> (installed-cells generic-id arg-id default-id out-id)
               (nb/seed-cell arg-id arg-value)
               (nb/seed-cell default-id :default))
        n1 (initialize-generic n0 generic-id default-id)]
    {:net n1
     :generic-id generic-id
     :arg-id arg-id
     :default-id default-id
     :out-id out-id}))

(deftest apply-closure-installs-compound-propagator
  (testing "closure helper applies a closure-valued cell"
    (let [closure-id (ids/new-node-id)
          in-id (ids/new-node-id)
          out-id (ids/new-node-id)
          closure-value (generic/handler-closure inc)
          n0 (-> (installed-cells closure-id in-id out-id)
                 (nb/seed-cell closure-id closure-value)
                 (nb/seed-cell in-id 10))
          [apply-prop n1] ((closure/p:apply-closure closure-id in-id out-id) n0)
          n2 (run-props n1 [apply-prop])]
      (is (= 11 (net/network-cell-strongest n2 out-id))))))

(deftest compile-symbols-resolve-from-network-dict
  (testing "eval-layered can use bindings already stored in the network dict"
    (let [in-id (ids/new-node-id)
          out-id (ids/new-node-id)
          n0 (-> (installed-cells in-id out-id)
                 (compile/bind-vars {'in in-id 'out out-id}))
          ctx (compile/eval-layered
               n0
               {'p/inc (prop/primitive-propagator inc)}
               {}
               '(p/inc in out))
          n1 (-> (:net ctx)
                 (nb/seed-cell in-id 10)
                 (run-props (:props ctx)))]
      (is (= 11 (net/network-cell-strongest n1 out-id))))))

(deftest make-generic-propagator-initializes-select-one-policy
  (testing "default and fixed policy are merged into the generic cell"
    (let [{:keys [net generic-id]} (one-arg-generic-net :x)
          generic-value (net/network-cell-strongest net generic-id)]
      (is (= :default
             (net/network-cell-strongest
              generic-value
              (net/network-dict-entry generic-value :generic/default))))
      (is (= :select-one
             (net/network-cell-strongest
              generic-value
              (net/network-dict-entry generic-value :generic/policy)))))))

(deftest generic-propagator-selects-one-matching-method
  (testing "one defined method with true matcher emits its handler result"
    (let [{:keys [net generic-id arg-id out-id]} (one-arg-generic-net 10)
          pred-id (ids/new-node-id)
          matcher-id (ids/new-node-id)
          handler-id (ids/new-node-id)
          n1 (-> net
                 (seed-closure-cell pred-id (generic/predicate-closure number?))
                 (seed-closure-cell matcher-id generic/all-args-match-closure)
                 (seed-closure-cell handler-id (generic/handler-closure (fn [x] [:number x]))))
          n2 (define-method n1 generic-id :number [pred-id] matcher-id handler-id)
          n3 (apply-generic n2 generic-id [arg-id] out-id)]
      (is (= [:number 10] (net/network-cell-strongest n3 out-id))))))

(deftest generic-propagator-falls-back-when-no-method-matches
  (testing "false matcher leaves result bank empty, so select-one emits default"
    (let [{:keys [net generic-id arg-id out-id]} (one-arg-generic-net "x")
          pred-id (ids/new-node-id)
          matcher-id (ids/new-node-id)
          handler-id (ids/new-node-id)
          n1 (-> net
                 (seed-closure-cell pred-id (generic/predicate-closure number?))
                 (seed-closure-cell matcher-id generic/all-args-match-closure)
                 (seed-closure-cell handler-id (generic/handler-closure (fn [x] [:number x]))))
          n2 (define-method n1 generic-id :number [pred-id] matcher-id handler-id)
          n3 (apply-generic n2 generic-id [arg-id] out-id)]
      (is (= :default (net/network-cell-strongest n3 out-id))))))

(deftest generic-propagator-contradicts-when-two-methods-match
  (testing "two usable branch results reduce to contradiction"
    (let [{:keys [net generic-id arg-id out-id]} (one-arg-generic-net 10)
          number-pred-id (ids/new-node-id)
          any-pred-id (ids/new-node-id)
          matcher-id (ids/new-node-id)
          number-handler-id (ids/new-node-id)
          any-handler-id (ids/new-node-id)
          n1 (-> net
                 (seed-closure-cell number-pred-id (generic/predicate-closure number?))
                 (seed-closure-cell any-pred-id (generic/predicate-closure (constantly true)))
                 (seed-closure-cell matcher-id generic/all-args-match-closure)
                 (seed-closure-cell number-handler-id
                                    (generic/handler-closure (fn [x] [:number x])))
                 (seed-closure-cell any-handler-id (generic/handler-closure (fn [x] [:any x]))))
          n2 (define-method n1 generic-id :number [number-pred-id] matcher-id number-handler-id)
          n3 (define-method n2 generic-id :any [any-pred-id] matcher-id any-handler-id)
          n4 (apply-generic n3 generic-id [arg-id] out-id)]
      (is (= :bool4/contradiction (net/network-cell-strongest n4 out-id))))))

(deftest late-generic-method-extension-affects-later-applications
  (testing "a later merged method branch changes later generic applications"
    (let [{:keys [net generic-id arg-id out-id]} (one-arg-generic-net "x")
          number-pred-id (ids/new-node-id)
          string-pred-id (ids/new-node-id)
          matcher-id (ids/new-node-id)
          number-handler-id (ids/new-node-id)
          string-handler-id (ids/new-node-id)
          n1 (-> net
                 (seed-closure-cell number-pred-id (generic/predicate-closure number?))
                 (seed-closure-cell string-pred-id (generic/predicate-closure string?))
                 (seed-closure-cell matcher-id generic/all-args-match-closure)
                 (seed-closure-cell number-handler-id
                                    (generic/handler-closure (fn [x] [:number x])))
                 (seed-closure-cell string-handler-id
                                    (generic/handler-closure (fn [x] [:string x]))))
          n2 (define-method n1 generic-id :number [number-pred-id] matcher-id number-handler-id)
          first-app (apply-generic n2 generic-id [arg-id] out-id)
          n3 (define-method n2 generic-id :string [string-pred-id] matcher-id string-handler-id)
          second-app (apply-generic n3 generic-id [arg-id] out-id)]
      (is (= :default (net/network-cell-strongest first-app out-id)))
      (is (= [:string "x"] (net/network-cell-strongest second-app out-id))))))

(deftest generic-operator-wraps-apply-generic
  (testing "operator convenience installs the same generic application"
    (let [{:keys [net generic-id arg-id out-id]} (one-arg-generic-net 10)
          pred-id (ids/new-node-id)
          matcher-id (ids/new-node-id)
          handler-id (ids/new-node-id)
          n1 (-> net
                 (seed-closure-cell pred-id (generic/predicate-closure number?))
                 (seed-closure-cell matcher-id generic/all-args-match-closure)
                 (seed-closure-cell handler-id (generic/handler-closure inc)))
          n2 (define-method n1 generic-id :inc [pred-id] matcher-id handler-id)
          [apply-prop n3] (((generic/p:generic-operator generic-id) arg-id out-id) n2)
          n4 (run-props n3 [apply-prop])]
      (is (= 11 (net/network-cell-strongest n4 out-id))))))
