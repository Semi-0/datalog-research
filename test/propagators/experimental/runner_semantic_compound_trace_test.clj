(ns propagators.experimental.runner-semantic-compound-trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.experimental.runner.compound-chain :as chain]
            [propagators.experimental.runner.compound-trace :as trace]
            [propagators.experimental.runner.semantic-compound-trace :as semantic]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.message :as msg]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- completed-network
  [execution]
  (let [result (:result execution)]
    (case (:status result)
      :completed (:network result)
      :failed (throw (:error result))
      (throw (ex-info "unknown runner result" {:result result})))))

(deftest topology-candidate-classification-is-semantic
  (let [plain-id (new-node-id)
        network-id (new-node-id)
        missing-id (new-node-id)
        n0 (-> (nb/install-cells [plain-id network-id])
               (nb/seed-cell network-id net/empty-net))]
    (is (false? (boolean
                 (semantic/topology-candidate? [(msg/message plain-id 1)] n0))))
    (is (semantic/topology-candidate?
         [(msg/message plain-id net/empty-net)]
         n0))
    (is (semantic/topology-candidate?
         [(msg/message network-id 1)]
         n0))
    (is (semantic/topology-candidate?
         [(msg/message missing-id 1)]
         n0))
    (is (semantic/topology-candidate?
         {:op :network/declare-cell :id (new-node-id)}
         n0))))

(deftest semantic-trace-matches-full-trace-on-real-compound-topology
  (doseq [depth [1 3 10]]
    (testing (str "depth " depth)
      (let [built (chain/build-vanilla-chain depth)
            state (select-keys built [:network :tasks])
            full (trace/run-with-trace state)
            semantic (semantic/run-with-semantic-trace state)]
        (is (= (:events full) (:events semantic)))
        (is (= (+ (* 4 depth) 2) (count (:events semantic))))
        (is (= (trace/semantic-snapshot (completed-network full))
               (trace/semantic-snapshot (completed-network semantic))))))))

(deftest ordinary-late-update-bypasses-topology-tracing
  (let [built (chain/build-vanilla-chain 30)
        setup (semantic/run-with-semantic-trace
               (select-keys built [:network :tasks]))
        settled (completed-network setup)
        [seeded tasks] (nb/seed-cell! settled
                                      tq/empty-queue
                                      (:input built)
                                      30)
        update (semantic/run-with-semantic-trace
                {:network seeded :tasks tasks})
        final-network (completed-network update)]
    (is (= 30 (net/network-cell-value final-network (:output built))))
    (is (empty? (:events update)))
    (is (pos? (get-in update [:metrics :ordinary-results] 0)))
    (is (= (get-in update [:metrics :ordinary-results])
           (get-in update [:metrics :ordinary-applies])))
    (is (zero? (get-in update [:metrics :topology-candidates] 0)))
    (is (zero? (get-in update [:metrics :topology-diffs] 0)))))

(deftest settled-rerun-performs-no-topology-diffs
  (let [built (chain/build-vanilla-chain 3)
        setup (semantic/run-with-semantic-trace
               (select-keys built [:network :tasks]))
        rerun (semantic/run-with-semantic-trace
               {:network (completed-network setup)
                :tasks (:tasks built)})]
    (is (= :completed (get-in rerun [:result :status])))
    (is (empty? (:events rerun)))
    (is (zero? (get-in rerun [:metrics :topology-diffs] 0)))))
