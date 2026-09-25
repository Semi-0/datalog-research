(ns propagators.debug-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [propagators.debug :as debug]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(deftest run-tasks-debug-runs-and-logs
  (let [in-id (ids/new-node-id)
        out-id (ids/new-node-id)
        lines (atom [])
        n0 (-> net/empty-net
               (nb/install-cell in-id 1 1)
               (nb/install-cell out-id))
        [prop-id n1] ((prop/construct-propagator
                       (fn [_inputs _outputs network]
                         [(message out-id
                                   (inc (net/network-cell-strongest network in-id)))])
                       [in-id]
                       [out-id])
                      n0)
        n2 (debug/run-tasks-debug (tq/enqueue tq/empty-queue prop-id)
                                  n1
                                  {:log-fn #(swap! lines conj %)})]
    (is (= 2 (net/network-cell-strongest n2 out-id)))
    (is (some #(str/includes? % "task") @lines))
    (is (some #(str/includes? % "msg") @lines))
    (is (some #(re-find #"\bn\d+\b" %) @lines))
    (is (not-any? #(str/includes? % "#uuid") @lines))
    (is (not-any? #(str/includes? % "NodeId") @lines))))

(deftest activation-profile-ranks-propagators-without-changing-evaluation
  (let [in-id (ids/new-node-id)
        middle-id (ids/new-node-id)
        out-id (ids/new-node-id)
        profile (debug/activation-profile)
        n0 (-> net/empty-net
               (nb/install-cell in-id 1 1)
               (nb/install-cell middle-id)
               (nb/install-cell out-id))
        [first-prop n1]
        ((prop/construct-propagator
          :debug/first
          (fn [_inputs _outputs _network]
            [(message middle-id 2)])
          [in-id]
          [middle-id])
         n0)
        [_second-prop n2]
        ((prop/construct-propagator
          :debug/second
          (fn [_inputs _outputs _network]
            [(message out-id 3)])
          [middle-id]
          [out-id])
         n1)
        result (debug/with-activation-profile
                 profile
                 (runner/completed-network
                  (runner/run-network
                   (tq/enqueue tq/empty-queue first-prop)
                   n2)))
        report (debug/activation-profile-report profile)
        by-name (into {} (map (juxt :prop-name identity)) (:by-name report))]
    (is (= 3 (net/network-cell-strongest result out-id)))
    (is (= 2 (get-in report [:totals :calls])))
    (is (= 1 (get-in by-name [:debug/first :calls])))
    (is (= 1 (get-in by-name [:debug/second :calls])))
    (is (every? #(<= 0.0 (:exclusive-ms %)) (:by-propagator report)))))
