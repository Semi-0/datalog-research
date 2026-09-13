(ns propagators.compiler-2.runtime.session-extension-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.boundary :as boundary]
            [propagators.compiler-2.runtime.boundary.effects :as effects]
            [propagators.compiler-2.runtime.operators.environment :as operators]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.program :as program]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn handled-effect
  [_services session _request]
  {:state (assoc session :test/handled true)
   :receipt {:status :handled}})

(defn failing-effect
  [_services _session _request]
  (throw (ex-info "handler failed" {:failure :expected})))

(defn invalid-effect-result
  [_services _session _request]
  :invalid)

(defn prop-count
  [network]
  (count (filter prop/prop? (vals (net/net-env network)))))

(defn bundle
  [id bindings effects]
  (extension/extension-bundle
   {:id id
    :bindings (constantly bindings)
    :effects effects}))

(defn effect
  [kind handler-symbol]
  {:effect/symbol (symbol (name kind))
   :boundary/port :environment
   :boundary/kind kind
   :effect/arities #{0}
   :effect/normalize (fn [_ _] {:status :ready :payload {}})
   :effect/identity-parts (constantly [])
   :effect/handler-symbol handler-symbol})

(defn request
  [kind]
  (boundary/environment-effect-request
   [kind :test]
   kind
   {}
   (state/stable-node-id :test :receipt kind)))

(defn perform
  [session kind]
  (let [request (request kind)
        prepared (update session :program/net
                         nb/ensure-cell (:boundary/receipt-id request))]
    (effects/perform-registered-environment-request prepared request)))

(deftest extension-installs-live-compound-bindings
  (let [extension (bundle :test/primitive [['answer 42]] [])
        installed (extension/install-session-extension
                   (state/empty-state) extension {})]
    (let [binding (env/resolve-binding
                   (:program/net installed)
                   (:program/env installed)
                   'answer)]
      (is (= 42
             (net/network-cell-strongest
              (:program/net installed)
              (env/binding-id binding)))))
    (is (= ['answer]
           (get-in installed
                   [:session/extensions :test/primitive :binding-symbols])))))

(deftest equivalent-installation-is-idempotent
  (let [extension (bundle :test/idempotent [['answer 42]] [])
        once (extension/install-session-extension
              (state/empty-state) extension {})
        twice (extension/install-session-extension once extension {})]
    (is (= (:program/env once) (:program/env twice)))
    (is (= (prop-count (:program/net once))
           (prop-count (:program/net twice))))
    (is (= (:session/extensions once)
           (:session/extensions twice)))))

(deftest extension-validation-is-atomic
  (let [initial (state/empty-state)
        duplicate (bundle :test/duplicate [['x 1] ['x 2]] [])
        collision (bundle
                   :test/collision
                   []
                   [(effect :environment/load-lain
                            'propagators.compiler-2.runtime.session-extension-test/handled-effect)])]
    (testing "duplicate symbols do not change the session"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"duplicate binding"
                            (extension/install-session-extension
                             initial duplicate {})))
      (is (nil? (get-in initial [:session/extensions :test/duplicate]))))
    (testing "handler collisions do not change the session"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"already registered"
                            (extension/install-session-extension
                             initial collision {:outbox-id [:test :outbox]})))
      (is (nil? (get-in initial [:session/extensions :test/collision]))))))

(deftest effect-invocation-normalization-is-explicit
  (let [effect (operators/load-lain-effect)]
    (is (= :invalid
           (:status (operators/normalize-effect-invocation effect {} []))))
    (is (= :wait
           (:status
            (operators/normalize-effect-invocation
             effect {} [value/nothing]))))
    (is (= {:status :ready :payload {:file "program.lain" :revision 0}}
           (operators/normalize-effect-invocation
            effect {} ["program.lain"])))))

(deftest runtime-session-catches-unexpected-activation-errors
  (let [initial (assoc (state/empty-state) :test/value :preserved)
        result (with-redefs
                 [effects/drain-environment-effects
                  (fn [_]
                    (throw (ex-info "unexpected activation" {:test true})))]
                 (effects/perform-boundary-effects initial))]
    (is (= :preserved (:test/value result)))
    (is (= "unexpected activation"
           (-> result :runtime/errors peek :message)))
    (is (= :effects/boundary
           (-> result :runtime/errors peek :source)))))

(defn assert-local-failure-receipt
  [source]
  (let [compiled (program/compiled-state source)
        result-id (get-in compiled [:compiled :cell])
        result (net/network-cell-strongest (:program/net compiled) result-id)
        statuses (keep (comp :boundary/status :strongest)
                       (vals (net/net-env result)))]
    (is (net/network? result))
    (is (some #{:failed} statuses))
    (is (empty? (effects/outbox-effects (:program/net compiled))))))

(deftest invalid-effect-arity-produces-a-local-failure-receipt
  (assert-local-failure-receipt "(load-lain)"))

(deftest invalid-effect-argument-produces-a-local-failure-receipt
  (assert-local-failure-receipt "(load-lain 1)"))

(deftest registered-handler-executes-inside-effect-boundary
  (let [kind :environment/test-handler
        custom (effect kind
                       'propagators.compiler-2.runtime.session-extension-test/handled-effect)
        initial (extension/install-session-extension
                 (state/empty-state)
                 (bundle :test/effect-only [] [custom])
                 {:outbox-id (state/stable-node-id :test :outbox)})
        performed (perform initial kind)]
    (is (:test/handled performed))
    (is (= :handled
           (get-in performed [:environment/effects [kind :test] :status])))))

(deftest mixed-extension-installs-bindings-and-effects-together
  (let [kind :environment/test-mixed
        custom (effect kind
                       'propagators.compiler-2.runtime.session-extension-test/handled-effect)
        installed (extension/install-session-extension
                   (state/empty-state)
                   (bundle :test/mixed [['answer 42]] [custom])
                   {:outbox-id (state/stable-node-id :test :outbox)})
        answer (env/resolve-binding (:program/net installed)
                                    (:program/env installed)
                                    'answer)
        operator (env/resolve-binding (:program/net installed)
                                      (:program/env installed)
                                      'test-mixed)]
    (is (= 42 (net/network-cell-strongest
               (:program/net installed) (env/binding-id answer))))
    (is (some? (net/network-cell-strongest
                (:program/net installed) (env/binding-id operator))))
    (is (contains? (:environment/handlers installed) [:environment kind]))))

(deftest handler-failures-become-receipts
  (doseq [[kind handler-symbol message]
          [[:environment/test-throw
            'propagators.compiler-2.runtime.session-extension-test/failing-effect
            "handler failed"]
           [:environment/test-invalid-result
            'propagators.compiler-2.runtime.session-extension-test/invalid-effect-result
            "must return a map"]]]
    (let [custom (effect kind handler-symbol)
          initial (assoc (state/empty-state)
                         :environment/handlers
                         (extension/register-effects {} [custom]))
          performed (perform initial kind)]
      (is (= :failed
             (get-in performed [:environment/effects [kind :test] :status])))
      (is (re-find (re-pattern message)
                   (pr-str
                    (get-in performed
                            [:environment/effects [kind :test]
                             :receipt :diagnostics])))))))

(deftest missing-handler-becomes-a-failed-receipt
  (let [kind :environment/missing-handler
        initial (assoc (state/empty-state) :environment/handlers {})
        performed (perform initial kind)]
    (is (= :failed
           (get-in performed [:environment/effects [kind :test] :status])))
    (is (re-find #"No environment effect handler"
                 (pr-str
                  (get-in performed
                          [:environment/effects [kind :test]
                           :receipt :diagnostics]))))))

(deftest malformed-handler-becomes-a-failed-receipt
  (let [kind :environment/malformed-handler
        initial (assoc-in (state/empty-state)
                          [:environment/handlers [:environment kind]]
                          {:id [:environment kind :malformed]
                           :handler :not-a-handler})
        performed (perform initial kind)]
    (is (= :failed
           (get-in performed [:environment/effects [kind :test] :status])))
    (is (re-find #"handler is invalid"
                 (pr-str
                  (get-in performed
                          [:environment/effects [kind :test]
                           :receipt :diagnostics]))))))
