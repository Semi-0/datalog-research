(ns ^{:deprecated "2026-09-30"} propagators.deprecated.ttms-accessor-switch-test
  "Historical two-switch hypothesis, not a compound-accessor acceptance gate.
  The pre-seeded independent endpoint does not model actual slot construction.
  Retention/recovery expectations are unestablished for the real accessor.
  Assertions are preserved for historical reproduction and still fail.
  Use propagators.ttms-real-accessor-test for the actual accessor lifecycle."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-accessor-switch :as accessor]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- premise [source epoch status]
  {:source source :timestamp epoch :premises-status status})

(defn- inject [network target base support]
  (core/eval-cell target
                  (message/message target (collection/content {:base base :support support}))
                  network))

(defn- publish [network target base support]
  (let [[tasks n] (inject network target base support)]
    (nb/run-propagators n tasks)))

(defn- fixture [direction]
  (let [[owner left right] (repeatedly 3 ids/new-node-id)
        [source target] (case direction :forward [left right] :backward [right left])
        owner-value (obj/compound-object {:x 10})
        cp (premise owner 1 :active)
        sp (premise source 1 :active)
        prepared (-> (nb/install-cells [owner left right])
                     (publish owner owner-value #{cp})
                     (publish source 10 #{sp}))
        {:keys [network tasks enabled]}
        (accessor/install-collection-switch prepared owner left right)]
    {:before prepared :network (nb/run-propagators network tasks) :tasks tasks
     :owner owner :owner-value owner-value :source source :target target
     :enabled enabled :collection-premise cp :source-premise sp}))

(deftest active-route-transports-value-and-collection-support-both-ways
  (doseq [direction [:forward :backward]]
    (let [{:keys [before network owner target collection-premise source-premise tasks]}
          (fixture direction)
          result (net/network-cell-strongest network target)]
      (is (= 10 (datum/layer-value result :base)))
      (is (= #{collection-premise source-premise} (datum/support-of result)))
      (is (= (net/network-cell-content before owner)
             (net/network-cell-content network owner)))
      (is (= (net/net-env network) (net/net-env (nb/run-propagators network tasks)))))))

(deftest reverse-feedback-must-preserve-independent-slot-evidence
  (doseq [direction [:forward :backward]]
    (let [{:keys [before network source]} (fixture direction)]
      (testing (name direction)
        (is (set/subset? (:support/observations (net/network-cell-content before source))
                         (:support/observations (net/network-cell-content network source)))
            "A round trip must not consume the original independent observation")))))

(deftest collection-withdrawal-and-recovery-must-not-require-source-reinjection
  (doseq [direction [:forward :backward]]
    (let [{:keys [network owner owner-value source target source-premise tasks]}
          (fixture direction)
          withdrawn (publish network owner value/nothing #{(premise owner 2 :retracted)})
          restored (publish withdrawn owner owner-value #{(premise owner 3 :active)})
          result (net/network-cell-strongest restored target)]
      (is (value/unusable? (net/network-cell-strongest withdrawn target)))
      (is (= #{(premise owner 2 :retracted)}
             (datum/support-of (net/network-cell-strongest withdrawn target))))
      (is (= 10 (datum/layer-value result :base)))
      (is (= #{(premise owner 3 :active) source-premise} (datum/support-of result)))
      (is (= (set (keys (net/net-env network))) (set (keys (net/net-env restored)))))
      (is (= (net/net-env restored) (net/net-env (nb/run-propagators restored tasks))))
      ;; Recovery means usability and correct support, not merely a concrete base.
      (let [reinjected (publish restored source 10 #{(premise source 2 :active)})
            value (net/network-cell-strongest reinjected target)]
        (is (= 10 (datum/layer-value value :base)))
        (is (not (value/unusable? value)))
        (is (= #{(premise owner 3 :active) (premise source 2 :active)}
               (datum/support-of value)))))))
