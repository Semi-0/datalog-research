(ns propagators.experimental.runner-compound-trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.core :as core]
            [propagators.experimental.runner.compound-chain :as chain]
            [propagators.experimental.runner.compound-trace :as trace]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- completed-network
  [traced]
  (let [result (:result traced)]
    (if (= :completed (:status result))
      (:network result)
      (throw (:error result)))))

(defn- setup-pair
  [depth]
  (let [built (chain/build-vanilla-chain depth)
        state (select-keys built [:network :tasks])
        vanilla (core/run-tasks (:tasks state) (:network state))
        traced (trace/run-with-trace state)]
    {:built built
     :vanilla vanilla
     :traced-network (completed-network traced)
     :events (:events traced)}))

(deftest traced-runner-preserves-vanilla-compound-semantics
  (doseq [depth [1 3 10]]
    (testing (str "depth " depth)
      (let [{:keys [vanilla traced-network]} (setup-pair depth)]
        (is (= (trace/semantic-snapshot vanilla)
               (trace/semantic-snapshot traced-network)))))))

(deftest scoped-trace-matches-the-full-recursive-trace
  (doseq [depth [1 3 10]]
    (testing (str "depth " depth)
      (let [built (chain/build-vanilla-chain depth)
            state (select-keys built [:network :tasks])
            full (trace/run-with-trace state)
            scoped (trace/run-with-scoped-trace state)]
        (is (= (:events full) (:events scoped)))
        (is (= (trace/semantic-snapshot (completed-network full))
               (trace/semantic-snapshot (completed-network scoped))))))))

(deftest trace-attributes-each-runtime-slot-sync-spawn
  (let [{:keys [traced-network events]} (setup-pair 3)]
    (is (= 14 (count events)))
    (doseq [{:keys [event cause spawn]} events]
      (is (= :propagator/spawned event))
      (is (= :outer (first (:network-path cause))))
      (is (= 2 (count (:network-path spawn))))
      (is (= :compound-object/network-slot
             (first (:propagator-kind cause))))
      (is (= :compound-object/slot-sync
             (first (:propagator-kind spawn))))
      (let [[_ [_ collection-id]] (:network-path spawn)
            nested (net/network-cell-value traced-network collection-id)
            installed (net/network-lookup-propagator nested (:propagator-id spawn))]
        (is (prop/prop? installed))
        (is (= (:propagator-kind spawn) (prop/prop-name installed)))))))

(deftest spawn-count-scales-with-real-compound-topology
  (doseq [depth [1 3 10]]
    (let [{:keys [events]} (setup-pair depth)]
      (is (= (+ (* 4 depth) 2) (count events))))))

(deftest settled-topology-does-not-report-duplicate-spawns
  (let [{:keys [built traced-network]} (setup-pair 3)
        rerun (trace/run-with-trace {:network traced-network
                                     :tasks (:tasks built)})]
    (is (= :completed (get-in rerun [:result :status])))
    (is (empty? (:events rerun)))))

(deftest late-update-has-semantic-parity-and-no-new-topology
  (let [{:keys [built traced-network]} (setup-pair 10)
        [seeded tasks] (nb/seed-cell! traced-network
                                      tq/empty-queue
                                      (:input built)
                                      30)
        vanilla (core/run-tasks tasks seeded)
        traced (trace/run-with-trace {:network seeded :tasks tasks})
        traced-network* (completed-network traced)]
    (is (= (trace/semantic-snapshot vanilla)
           (trace/semantic-snapshot traced-network*)))
    (is (= 30 (net/network-cell-value traced-network* (:output built))))
    (is (empty? (:events traced)))))

(deftest trace-summary-preserves-cause-and-spawn-kinds
  (let [{:keys [events]} (setup-pair 1)
        summary (trace/trace-summary events)]
    (is (= 6 (:event-count summary)))
    (is (= 6 (reduce + (vals (:by-cause-kind summary)))))
    (is (= 6 (reduce + (vals (:by-spawn-kind summary)))))
    (is (= {2 6} (:by-network-depth summary)))))

(deftest trace-boundaries-fail-explicitly
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"max trace depth"
       (trace/propagator-inventory net/empty-net -1)))
  (let [failures (atom [])
        evaluator (trace/traced-patch-evaluator identity)]
    (evaluator :not-an-envelope
               net/empty-net
               {:success (fn [& _]
                           (throw (ex-info "unexpected success" {})))
                :fail #(swap! failures conj %)})
    (is (= 1 (count @failures)))
    (is (re-find #"expected an activation envelope"
                 (ex-message (first @failures))))))
