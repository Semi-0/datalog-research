(ns propagators.compiler-2.runtime.clock-test
  "Reliable clock contracts plus a deprecated event-to-TUI diagnostic."
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.session.clock :as clock]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.message :refer [message]]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- request [commit-id index expected-version text]
  {:op :tui/commit-version
   :commit-id commit-id
   :client-id "clock-test"
   :index index
   :expected-version expected-version
   :text text})

(defn- install-clock!
  [session interval]
  (let [command #(runtime/handle-command! session %)]
    (command {:op :tui/register
              :client-id "clock-test"
              :mode :versioned-premise})
    (command
     (request
      "00000000-0000-0000-0000-000000000201" 0 nil
      (str "(load-primitive-environment "
           "\"extensions/runtime_clock.clj\" "
           ":extensions.runtime-clock/primitive-bindings 0)")))
    (command
     (request
      "00000000-0000-0000-0000-000000000202" 1 nil
      (str "(clock-in " interval ")")))))

(defn- first-subscription
  [session]
  (first (:clock/subscriptions @session)))

(deftest clock-update-is-a-source-aware-ttms-publication
  (let [subscription {:clock/id :clock-a :clock/target-id :out}
        update (clock/clock-update subscription 7 123456)]
    (is (= 123456 (datum/layer-value update :base)))
    (is (= #{{:source (clock/clock-source-id subscription) :timestamp 7 :premises-status :active}}
           (datum/support-of update)))
    (is (collection/content? (collection/content update)))))

(defn- inject [network id datum]
  (second (core/eval-cells [(message id (collection/content datum))] network)))

(deftest newer-ticks-and-withdrawal-preserve-frozen-clock-values
  (let [id (ids/new-node-id)
        subscription {:clock/id :clock-a :clock/target-id id}
        first (inject (nb/install-cell net/empty-net id) id
                      (clock/clock-update subscription 1 1000))
        next (inject first id (clock/clock-update subscription 2 2000))
        withdrawn (inject next id (clock/clock-retraction subscription 3))]
    (is (= 1000 (datum/layer-value (net/network-cell-strongest first id) :base)))
    (is (= 2000 (datum/layer-value (net/network-cell-strongest next id) :base)))
    (is (value/nothing? (datum/layer-value (net/network-cell-strongest withdrawn id) :base)))
    (is (= #{{:source (clock/clock-source-id subscription) :timestamp 3 :premises-status :retracted}}
           (datum/layer-value (net/network-cell-strongest withdrawn id) :premise-state)))))

(defn ^:deprecated loaded-clock-display-result
  "Reproduce the unsupported flat-GUR event-to-TUI display crossing.

  The intended result is 1000. The current clock prototype returns nothing
  because topology lowering does not preserve its dictionary-backed event-cell
  declaration. This function is diagnostic evidence, not a release gate."
  []
  (let [session (runtime/new-session)]
    (try
      (install-clock! session 20)
      (runtime/stop-clocks! session)
      (let [[subscription-id _subscription] (first-subscription session)]
        (clock/tick-clock! session subscription-id (constantly 1000))
        (get-in (runtime/read-tui-view
                 @session {:client-id "clock-test"})
                [:blocks 2 :value]))
      (finally
        (runtime/stop-clocks! session)))))

(defn ^:deprecated loaded-clock-retraction-result
  "Exercise the full experimental clock loader and block-retraction path.

  Kept as an opt-in diagnostic because compiling the prototype exceeds the
  three-second per-test limit."
  []
  (let [session (runtime/new-session)
        command #(runtime/handle-command! session %)]
    (try
      (install-clock! session 20)
      (let [[subscription-id subscription] (first-subscription session)]
        (command
         (request
          "00000000-0000-0000-0000-000000000203" 1 0
          "(define stopped)"))
        (let [epoch-key (or (:clock/source-id subscription) subscription-id)
              epoch-after-retraction (get-in @session
                                             [:clock/epochs epoch-key])]
          (clock/tick-clock! session subscription-id (constantly 1001))
          {:active? (clock/subscription-active? @session subscription)
           :epoch-stable?
           (= epoch-after-retraction
              (get-in @session [:clock/epochs epoch-key]))
           :target
           (net/network-cell-strongest
            (:program/net @session) (:clock/target-id subscription))}))
      (finally
        (runtime/stop-clocks! session)))))

(deftest retracted-premise-disables-clock-subscription
  (let [state-id (ids/new-node-id)
        premise-id :clock-test/premise
        context {:premise/id premise-id
                 :premise/state-cell state-id}
        subscription {:clock/id :clock-test/subscription
                      :clock/target-id (ids/new-node-id)
                      :clock/contexts [context]}
        active-net
        (-> net/empty-net
            (nb/install-cell state-id)
            (nb/seed-cell
             state-id
             (tms/distributed-premise-update premise-id 0 true)))
        retracted-net
        (nb/seed-cell
         active-net state-id
         (tms/distributed-premise-update premise-id 1 false))]
    (is (true? (clock/subscription-active?
                {:program/net active-net} subscription)))
    (is (false? (clock/subscription-active?
                 {:program/net retracted-net} subscription)))))
