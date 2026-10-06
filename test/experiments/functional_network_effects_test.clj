(ns experiments.functional-network-effects-test
  (:require [clojure.test :refer [deftest is]]
            [experiments.functional-network :as experiment]
            [experiments.functional-network.compiler :as language]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.compiler-2.runtime.boundary.effects :as effects]
            [propagators.datastructures.compound-object :as obj]
            [propagators.network-builder :as nb]
            [propagators.network :as net]))

;; Only the boundary handler performs this controlled external action.
(def executions (atom []))

(defn handle-emission [_ session request]
  (let [value (get-in request [:boundary/payload :value])]
    (swap! executions conj value)
    (if (= 99 value)
      (throw (ex-info "Controlled handler failure" {:value value}))
      {:state session :receipt {:status :handled}})))

(def base-session
  ;; Fixture initialization is namespace setup, outside individual test timers.
  (extension/install-session-extension
   (update (state/empty-state) :program/net nb/ensure-cell (state/boundary-outbox-id))
   (extension/extension-bundle
    {:id :experiment/emission
     :bindings [['apply experiment/apply-operator]]
     :effects [{:effect/symbol 'emit
                :boundary/port :environment
                :boundary/kind :experiment/emission
                :effect/arities #{1}
                :effect/normalize (fn [_ [value]] {:status :ready :payload {:value value}})
                :effect/identity-parts (juxt :value)
                :effect/handler-symbol
                'experiments.functional-network-effects-test/handle-emission}]})
   {:outbox-id (state/boundary-outbox-id)}))

(def visitor
  "(define visit
     (network (xs)
       (let [empty? (= xs :compiler-2/list-empty)]
         (when (switch true (not empty?))
           (emit (car xs))
           (visit (cdr xs)))
         xs)))")

(defn compile-session [source]
  (let [compiled (language/compile-source
                  source (:program/env base-session)
                  {:net (:program/net base-session)
                   :seed [:experiment/effect source]})]
    {:compiled compiled
     :session (assoc base-session :program/net
                     (nb/run-propagators (:net compiled) (:props compiled)))}))

(defn visit-session [arguments]
  (:session (compile-session (str "(let [] " visitor " (visit " arguments "))"))))

(deftest closure-declaration-does-not-emit-or-execute-effects
  (let [before (count @executions)
        {:keys [session]} (compile-session "(define deferred (network () (emit 42)))")]
    (is (empty? (effects/outbox-effects (:program/net session))))
    (is (= before (count @executions)))))

(deftest recursive-linked-list-gur-emits-without-executing
  (let [before (count @executions)
        session (visit-session "(list 1 2 3)")
        requests (effects/outbox-effects (:program/net session))]
    (is (= [1 2 3] (sort (map #(get-in % [:boundary/payload :value]) requests))))
    (is (= before (count @executions)))))

(deftest boundary-execution-delivers-receipts-and-does-not-replay
  (let [before (count @executions)
        session (visit-session "(list 1 2 3)")
        drained (effects/drain-environment-effects session)
        replayed (effects/drain-environment-effects drained)]
    (is (= [1 2 3] (sort (drop before @executions))))
    (is (every? #(= :handled (:status %)) (vals (:environment/effects drained))))
    (doseq [request (effects/outbox-effects (:program/net session))]
      (is (= :handled
             (:boundary/status
              (obj/slot-value
               (net/network-cell-strongest (:program/net drained) (:boundary/receipt-id request))
               (state/receipt-slot-key (:boundary/id request)))))))
    (is (= (+ before 3) (count @executions)))
    (is (= (:environment/effects drained) (:environment/effects replayed)))))

(deftest handler-failure-becomes-a-failed-receipt
  (let [session (visit-session "(list 99)")
        drained (effects/drain-environment-effects session)
        entry (first (vals (:environment/effects drained)))]
    (is (= :failed (:status entry)))
    (is (= "Controlled handler failure" (get-in entry [:receipt :diagnostics 0 :message])))))

(deftest unavailable-effect-argument-waits-and-wakes-later
  (let [before (count @executions)
        {:keys [compiled session]} (compile-session "(let-cell [a] (emit a) a)")
        argument (:cell compiled)
        seeded (nb/seed-cell (:program/net session) argument 42)
        activated (nb/run-propagators seeded (nb/neighbor-propagator-ids seeded argument))
        drained (effects/drain-environment-effects (assoc session :program/net activated))]
    (is (empty? (effects/outbox-effects (:program/net session))))
    (is (= 1 (count (effects/outbox-effects activated))))
    (is (= [42] (vec (drop before @executions))))
    (is (= :handled (:status (first (vals (:environment/effects drained))))))))

(deftest ordinary-apply-constructor-wraps-an-effectful-visitor
  (let [before (count @executions)
        source (str "(let-cell [trace] " visitor
                    " (define define-constraint"
                    "   (network (name definition-network)"
                    "     (define name (network (args)"
                    "       (apply definition-network args) args))))"
                    " (define-constraint trace visit)"
                    " (trace (list (list 1 2 3))))")
        {:keys [session]} (compile-session source)
        requests (effects/outbox-effects (:program/net session))]
    (is (= before (count @executions)))
    (is (= 3 (count requests)))
    (let [drained (effects/drain-environment-effects session)]
      (is (= [1 2 3] (sort (drop before @executions))))
      (is (every? #(= :handled (:status %)) (vals (:environment/effects drained)))))))
