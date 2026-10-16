(ns propagators.experimental.live-environment-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [examples.lain.live-environment.application :as application]
            [examples.lain.live-environment.callable :as callable]
            [propagators.application-dispatch :as dispatch]
            [propagators.cell-evaluator :as evaluator]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.application :as compiler-application]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur.flat :as gur]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.runner :as runner]
            [propagators.stdlib.premise-state :as premise-state]))

(use-fixtures :each
  (fn [run]
    (binding [runner/*advance-transform*
              (runner/compose-advance-transforms runner/*advance-transform*
                                                 application/advance-transform)]
      (run))))

(defn- binding-id [session name]
  (env/resolve-binding-id (:program/net session) (:program/env session) name))

(defn- strongest [session name]
  (net/network-cell-strongest (:program/net session) (binding-id session name)))

(defn- setup []
  (let [session (runtime/new-session)
        source (ids/new-node-id)
        bundle (extension/extension-bundle
                {:id ::live-callable :effects []
                 :bindings [['f (callable/operator source)]]})]
    (runtime/install-session-extension! session ttms/session-extension
                                        {:outbox-id (runtime-ids/boundary-outbox-id)})
    (runtime/install-session-extension! session bundle {})
    (let [loaded (runtime/extend-source!
                  session
                  {:source (str "(define first-version (network [v] (+ v 1))) "
                                "(define second-version (network [v] (+ v 2))) "
                                "(define answer (f 10)) "
                                "(define downstream (* answer 2))")})]
      (is (nil? (:error loaded)))
      {:session @session :source source})))

(defn- publish [context base timestamp status]
  (let [source (:source context)
        premise #{{:source source :timestamp timestamp :premises-status status}}
        update (if (= :retracted status)
                 (collection/content {:premise-state premise})
                 (collection/content {:base base :support premise
                                      :premise-state premise}))
        session (:session context)
        [tasks installed] (evaluator/evaluate-all
                           [(message/message source update)] (:program/net session))
        settled (runner/completed-network (runner/run-network tasks installed))]
    (assoc-in context [:session :program/net] settled)))

(deftest supported-callable-replaces-existing-results
  (let [initial (setup)
        first-function (strongest (:session initial) 'first-version)
        second-function (strongest (:session initial) 'second-version)
        first-state (publish initial first-function 1 :active)
        second-state (publish first-state second-function 2 :active)]
    (is (= 11 (data/payload (strongest (:session first-state) 'answer))))
    (is (= 22 (data/payload (strongest (:session first-state) 'downstream))))
    (is (= 12 (data/payload (strongest (:session second-state) 'answer))))
    (is (= 24 (data/payload (strongest (:session second-state) 'downstream))))
    (is (= (binding-id (:session first-state) 'answer)
           (binding-id (:session second-state) 'answer)))
    (let [result (strongest (:session second-state) 'answer)]
      (is (contains? (datum/support-of result)
                     {:source (:source initial) :timestamp 2
                      :premises-status :active})))
    (testing "duplicate publication and repeated activation do not grow topology"
      (let [duplicate (publish second-state second-function 2 :active)
            before (:program/net (:session second-state))
            after (:program/net (:session duplicate))]
        (is (= (:graph before) (:graph after)))
        (is (= (:relationship before) (:relationship after)))
        (is (= (count (:env before)) (count (:env after))))))))

(deftest callable-withdrawal-and-recovery
  (let [initial (setup)
        first-function (strongest (:session initial) 'first-version)
        second-function (strongest (:session initial) 'second-version)
        active (publish initial first-function 1 :active)
        withdrawn (publish active value/nothing 2 :retracted)
        recovered (publish withdrawn second-function 3 :active)]
    (is (value/unusable? (strongest (:session withdrawn) 'answer)))
    (is (value/unusable? (strongest (:session withdrawn) 'downstream)))
    (is (= 12 (data/payload (strongest (:session recovered) 'answer))))
    (is (= 24 (data/payload (strongest (:session recovered) 'downstream))))))

(defn- returned-callable-state []
  (let [session (runtime/new-session)
        source (ids/new-node-id)
        bundle (extension/extension-bundle
                {:id ::returned-callable :effects []
                 :bindings [['live-make-adder (callable/operator source)]]})]
    (runtime/install-session-extension! session ttms/session-extension
                                        {:outbox-id (runtime-ids/boundary-outbox-id)})
    (runtime/install-session-extension! session bundle {})
    (let [loaded (runtime/extend-source!
                  session
                  {:source (str "(define make-adder (network [a] (network [b] (+ a b)))) "
                                "(define replacement (network [a] (network [b] (+ (+ a b) 1)))) "
                                "(define ordinary-returned (make-adder 10)) "
                                "(define ordinary (ordinary-returned 20)) "
                                "(define live-returned (live-make-adder 10)) "
                                "(define actual (live-returned 20))")})
          initial {:session @session :source source}
          active (publish initial (strongest @session 'make-adder) 1 :active)]
      (is (nil? (:error loaded)))
      active)))

(deftest returned-callables-preserve-ordinary-application
  (let [active (returned-callable-state)
        returned (strongest (:session active) 'live-returned)]
      (is (= 30 (data/payload (strongest (:session active) 'ordinary))))
      (is (not (value/unusable? returned)))
      (is (gur/recursive-closure? (datum/layer-value returned :base)))
      (is (not (gur/recursive-closure? returned)))
      ;; Keep the original acceptance result; the opt-in evaluator must fix it.
      (is (= 30 (data/payload (strongest (:session active) 'actual))))))

(deftest raw-application-control-rejects-layered-callables
  ;; Isolate the raw dispatcher even when another suite loaded the optional
  ;; supported-callable handler. Restore the multimethod after this test.
  (let [raw (get-method dispatch/application-effects :default)]
    (with-redefs [dispatch/application-effects raw]
      (binding [runner/*advance-transform* identity]
        (let [active (returned-callable-state)]
          (is (= 30 (data/payload (strongest (:session active) 'ordinary))))
          (is (value/contradiction? (strongest (:session active) 'actual))))))))

(deftest returned-callables-replace-withdraw-and-recover
  (let [active (returned-callable-state)
        replacement (strongest (:session active) 'replacement)
        original (strongest (:session active) 'make-adder)
        changed (publish active replacement 2 :active)
        withdrawn (publish changed value/nothing 3 :retracted)
        recovered (publish withdrawn original 4 :active)
        stale (publish recovered replacement 2 :active)
        duplicate (publish stale original 4 :active)]
    (is (= 31 (data/payload (strongest (:session changed) 'actual))))
    (is (value/unusable? (strongest (:session withdrawn) 'actual)))
    (is (contains? (premise-state/states-of (strongest (:session withdrawn) 'actual))
                   {:source (:source active) :timestamp 3 :premises-status :retracted}))
    (is (= 30 (data/payload (strongest (:session recovered) 'actual))))
    (is (= 30 (data/payload (strongest (:session stale) 'actual))))
    (is (contains? (datum/support-of (strongest (:session recovered) 'actual))
                   {:source (:source active) :timestamp 4 :premises-status :active}))
    (let [before (:program/net (:session stale))
          after (:program/net (:session duplicate))]
      (is (= (:graph before) (:graph after)))
      (is (= (:relationship before) (:relationship after)))
      (is (= (count (:env before)) (count (:env after)))))))

(defn- rerun-applications [context]
  (let [network (:program/net (:session context))
        tasks (mapv #(gur/stable-node-id [(:application-id %) :apply-prop])
                    (compiler-application/application-topologies network))]
    (assoc-in context [:session :program/net]
              (runner/completed-network (runner/run-network tasks network)))))

(deftest supported-operator-cell-needs-no-public-facade
  (let [session (runtime/new-session)]
    (runtime/install-session-extension! session ttms/session-extension
                                        {:outbox-id (runtime-ids/boundary-outbox-id)})
    (is (nil? (:error
               (runtime/extend-source!
                session
                {:source (str "(define first-version (network [v] (+ v 1))) "
                              "(define second-version (network [v] (+ v 2))) "
                              "(define selected) (define argument) "
                              "(define answer (selected argument))")}))))
    (let [operator-id (binding-id @session 'selected)
          argument-id (binding-id @session 'argument)
          initial {:session @session :source operator-id}
          first-function (strongest @session 'first-version)
          second-function (strongest @session 'second-version)
          chosen (publish initial first-function 1 :active)
          active (publish (assoc chosen :source argument-id) 10 1 :active)
          changed (publish active 20 2 :active)
          withdrawn (publish changed value/nothing 3 :retracted)
          recovered (publish withdrawn 4 4 :active)
          switched (publish (assoc recovered :source operator-id)
                            second-function 2 :active)
          rerun (rerun-applications switched)]
      (is (= 11 (data/payload (strongest (:session active) 'answer))))
      (is (= 21 (data/payload (strongest (:session changed) 'answer))))
      (is (value/unusable? (strongest (:session withdrawn) 'answer)))
      (is (contains? (premise-state/states-of (strongest (:session withdrawn) 'answer))
                     {:source argument-id :timestamp 3 :premises-status :retracted}))
      (is (= 5 (data/payload (strongest (:session recovered) 'answer))))
      (is (= 6 (data/payload (strongest (:session switched) 'answer))))
      (is (= #{{:source operator-id :timestamp 2 :premises-status :active}
               {:source argument-id :timestamp 4 :premises-status :active}}
             (datum/support-of (strongest (:session switched) 'answer))))
      (testing "an argument update does not reinstall the application body"
        (is (= (:graph (:program/net (:session active)))
               (:graph (:program/net (:session changed))))))
      (testing "explicitly rerunning applications is topology-idempotent"
        (let [before (:program/net (:session switched))
              after (:program/net (:session rerun))]
          (is (= 6 (data/payload (strongest (:session rerun) 'answer))))
          (is (= (:graph before) (:graph after)))
          (is (= (:relationship before) (:relationship after)))
          (is (= (count (:env before)) (count (:env after)))))))))

(deftest persistent-submissions-share-an-environment
  (let [session (runtime/new-session)
        first-load (runtime/extend-source!
                    session {:source "(define x) (define y (+ x 1))"})
        initial-net (:program/net @session)
        second-load (runtime/extend-source! session {:source "(-> 10 x)"})
        third-load (runtime/extend-source! session {:source "(define z (* y 2))"})]
    (is (every? nil? (map :error [first-load second-load third-load])))
    (is (= 22 (data/payload (strongest @session 'z))))
    (is (every? #(contains? (:env (:program/net @session)) %)
                (keys (:env initial-net))))))

(deftest scalar-freshness-does-not-rebuild-consumers
  (let [session (runtime/new-session)]
    (runtime/install-session-extension! session ttms/session-extension
                                        {:outbox-id (runtime-ids/boundary-outbox-id)})
    (is (nil? (:error (runtime/extend-source!
                      session {:source "(define x) (define y (+ x 1))"}))))
    (let [initial {:session @session :source (binding-id @session 'x)}
          first-state (publish initial 10 1 :active)
          second-state (publish first-state 20 2 :active)
          before (:program/net (:session first-state))
          after (:program/net (:session second-state))]
      (is (= 11 (data/payload (strongest (:session first-state) 'y))))
      (is (= 21 (data/payload (strongest (:session second-state) 'y))))
      (is (= (:graph before) (:graph after)))
      (is (= (:relationship before) (:relationship after)))
      (is (= (count (:env before)) (count (:env after)))))))
