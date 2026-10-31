(ns propagators.compiler-2.runtime.session-extension-test
  (:require [clojure.test :refer [deftest is testing]]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.primitives :as primitives]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.experimental.visualization.data :as data]
            [propagators.runner :as runner]
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

(defn replacement-effect
  [_services session _request]
  {:state (assoc session :test/redefined true)
   :receipt {:status :redefined}})

(defn prop-count
  [network]
  (count (filter prop/prop? (vals (net/net-env network)))))

(defn bundle
  [id bindings effects]
  (extension/extension-bundle
   {:id id
    :bindings bindings
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

(deftest extension-declaration-rejects-deferred-bindings
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"declared values"
       (extension/extension-bundle
        {:id :test/deferred
         :bindings (constantly [['answer 42]])
         :effects []}))))

(deftest extension-snapshots-a-mutable-binding-collection
  (let [source (java.util.ArrayList.)]
    (.add source ['answer 42])
    (let [declared (extension/extension-bundle
                    {:id :test/snapshot
                     :bindings source
                     :effects []})]
      (.clear source)
      (is (= [['answer 42]]
             (extension/extension-bindings declared)))
      (is (= [['answer 42]]
             (get (extension/extension-declaration declared)
                  :extension/bindings))))))

(deftest changed-declaration-cannot-reuse-an-extension-id
  (let [first-extension (bundle :test/fixed-id [['answer 42]] [])
        changed-extension (bundle :test/fixed-id [['answer 43]] [])
        installed {:session/extensions
                   {:test/fixed-id
                    {:declaration
                     (extension/extension-declaration first-extension)}}}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"already installed"
         (extension/install-session-extension
          installed changed-extension {})))
    (is (= (extension/extension-declaration first-extension)
           (get-in installed
                   [:session/extensions :test/fixed-id :declaration])))))

(deftest descendant-extensions-preserve-parent-session-values
  (let [parent (extension/install-session-extension
                (state/empty-state)
                (bundle :test/parent [['x 1]] [])
                {})
        child (extension/install-session-extension
               parent
               (bundle :test/child [['y 2]] [])
               {})
        value-at (fn [session symbol]
                   (net/network-cell-strongest
                    (:program/net session)
                    (env/binding-id
                     (env/resolve-binding
                      (:program/net session)
                      (:program/env session)
                      symbol))))]
    (is (not= (:program/env parent) (:program/env child)))
    (is (= 1 (value-at parent 'x)))
    (is (= 1 (value-at child 'x)))
    (is (= 2 (value-at child 'y)))
    (is (nil? (get-in parent [:session/extensions :test/child])))
    (is (= :installed
           (get-in child [:session/extensions :test/child :status])))))

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

(deftest installed-handler-is-fixed-across-var-redefinition
  (let [kind :environment/test-fixed-handler
        custom (effect kind
                       'propagators.compiler-2.runtime.session-extension-test/handled-effect)
        declared (bundle :test/fixed-handler [] [custom])
        installed (extension/install-session-extension
                   (state/empty-state)
                   declared
                   {:outbox-id (state/stable-node-id :test :outbox)})
        [reinstalled performed]
        (with-redefs [handled-effect replacement-effect]
          (let [reinstalled (extension/install-session-extension
                             installed declared {})]
            [reinstalled (perform reinstalled kind)]))]
    (is (identical? installed reinstalled))
    (is (:test/handled performed))
    (is (nil? (:test/redefined performed)))
    (is (= :handled
           (get-in performed
                   [:environment/effects [kind :test] :status])))))

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


;; ---------------------------------------------------------------------------
;; Native per-intent emission (W18): a component declares one native boundary
;; effect per distinct CURRENT intent. The same Clojure function runs in two
;; sessions, so only session ownership may keep their state apart.
;; ---------------------------------------------------------------------------

(def emitted-deliveries (atom []))

(def intents-effect
  {:effect/symbol 'test:declare-request
   :boundary/port :harness
   :boundary/kind :test/request
   :effect/arities #{1}
   :effect/handler-symbol 'propagators.compiler-2.runtime.session-extension-test/deliver-intent
   :effect/identity-parts (comp vector :id)
   :effect/normalize (fn [_ [intents]]
                       (cond
                         (nil? intents) {:status :wait}
                         (not (every? map? intents)) {:status :invalid :reason :invalid-intents}
                         :else {:status :ready :payload (vec intents)}))})

(defn deliver-intent
  "Session-owned transport handler: it performs the host call and returns the
  receipt; it never decides whether the intent is eligible."
  [services session request]
  (swap! emitted-deliveries conj {:session-id (get-in request [:boundary/payload :session])
                                  :effect-id (:boundary/id request)})
  {:state session
   :receipt {:status :delivered :delivered true}})

(defn intents-bundle [session-id]
  (let [session session-id]
    (extension/extension-bundle
     {:id (symbol (str "test/emitter-" session-id))
      :bindings
      [['test:current-intents
        (primitives/leaf-operator 'test:current-intents
         {:arity 1 :inputs [0] :input-kinds [:facts] :output nil
          :session session :kind :value}
         (fn [log]
           (mapv (fn [event] {:kind :test/request :id (:id event) :record (:record event)
                              :session session})
                 (facts/ordered-facts log))))]
       ['test:emit-intents (primitives/intent-effect-operator intents-effect)]]
      :effects [intents-effect]})))

(def intents-source
  "(define emit:in)
   (define emit:intents (test:current-intents emit:in))
   (define emit:emitted (test:emit-intents emit:intents))")

(defn open-emitter [session-id]
  (let [session (binding [runner/*advance-transform*
                          (runner/compose-advance-transforms runner/*advance-transform*
                                                             (primitives/runner-transform session-id))]
                  (loader/load-session-from-source intents-source
                                                   {:extensions [(intents-bundle session-id)]}))]
    {:session-id session-id :session session
     :in (env/resolve-binding-id (:program/net @session) (:program/env @session)
                                 'emit:in)}))

(defn emit! [owner log]
  (primitives/publish-facts! (:session owner) (:session-id owner) (:in owner) log)
  owner)

(defn emitted-requests [owner]
  (filter #(= :test/request (:boundary/kind %))
          (effects/outbox-effects (:program/net @(:session owner)))))

(deftest two-concurrent-intents-own-two-native-effect-cells
  (let [session-id "per-intent"
        owner (open-emitter session-id)]
    (emit! owner (facts/from-facts session-id
                     [(facts/fact session-id "second" 1 :record/submitted {:record "b"})
                      (facts/fact session-id "first" 0 :record/submitted {:record "a"})]))
    (let [requests (emitted-requests owner)]
      (is (= 2 (count requests)) "two current intents declare two requests, not one collapsed cell")
      (is (= #{[:test/request "first"] [:test/request "second"]}
             (into #{} (map :boundary/id) requests))
          "effect identity is each intent's own identity")
      (is (= 2 (count (into #{} (map :boundary/receipt-slot) requests)))
          "each intent keeps a distinct stable receipt slot key")
      (is (= #{"a" "b"} (into #{} (map (comp :record :boundary/payload)) requests))
          "concurrent intents coexist instead of overwriting one shared request cell"))))

(deftest current-intents-are-a-retractable-value-not-an-accumulating-domain
  (let [session-id "value-leaf"
        owner (open-emitter session-id)]
    (emit! owner (facts/from-facts session-id
                     [(facts/fact session-id "one" 0 :record/submitted {:record "a"})]))
    (let [intents-id (env/resolve-binding-id (:program/net @(:session owner))
                                             (:program/env @(:session owner))
                                             'emit:intents)
          read (fn [] (data/payload (net/network-cell-strongest (:program/net @(:session owner))
                                                               intents-id)))]
      (is (sequential? (read)) "current intents are one bounded value leaf, not a fact log")
      (is (= ["one"] (mapv :id (read))))
      (is (= [[:test/request "one"]] (mapv :boundary/id (emitted-requests owner))))
      (emit! owner (facts/from-facts session-id
                       [(facts/fact session-id "two" 1 :record/submitted {:record "b"})]))
      (is (= #{"one" "two"} (into #{} (map :id) (read)))
          "a later intent adds its own cell instead of replacing the current set")
      (is (= 2 (count (emitted-requests owner)))))))

(deftest same-clojure-function-in-two-sessions-stays-session-owned
  (let [a (open-emitter "owner-a")
        b (open-emitter "owner-b")]
    (emit! a (facts/from-facts "owner-a"
                 [(facts/fact "owner-a" "one" 0 :record/submitted {:record "a"})]))
    (is (= [[:test/request "one"]] (mapv :boundary/id (emitted-requests a))))
    (is (empty? (emitted-requests b)) "an identical function must not emit into another session")
    (is (empty? @emitted-deliveries) "declaring an effect never invokes its handler")
    (testing "delivery uses the registered session handler through the native seam"
      (let [request (first (emitted-requests a))
            receipt-id (env/resolve-binding-id (:program/net @(:session a))
                                               (:program/env @(:session a))
                                               'emit:intents)
            performed (effects/perform-registered-environment-request
                       @(:session a)
                       (assoc request :boundary/receipt-id receipt-id))]
        (is (= {:status :delivered :delivered true}
               (get-in performed [:environment/effects [:test/request "one"] :receipt])))
        (is (= [{:session-id "owner-a" :effect-id [:test/request "one"]}]
               @emitted-deliveries)
            "the handler resolved from THIS session's registry, not a global table")))
    (testing "effect slot keys come from the runtime identity scheme"
      (is (= (runtime-ids/effect-slot-key [:test/request "one"])
             (runtime-ids/effect-slot-key [:test/request "one"])))
      (is (not= (runtime-ids/effect-slot-key [:test/request "one"])
                (runtime-ids/effect-slot-key [:test/request "two"]))))))
