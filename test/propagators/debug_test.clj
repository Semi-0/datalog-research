(ns propagators.debug-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [propagators.core :as core]
            [propagators.debug :as debug]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

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
