(ns propagators.kernel-io-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.core :as core]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.io :as io]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.reality :as reality]
            [propagators.stdlib.prop :as prop]))

(defn- strongest [n id]
  (net/network-cell-strongest n id))

(deftest net-preserves-io-through-reconstruction
  (let [a (ids/new-node-id)
        b (ids/new-node-id)
        io-state {:queue [(io/prop-delivery a)]
                  :queued-props #{a}
                  :inbox [(io/io-record :in a (message/message a 1))]
                  :outbox [(io/io-record :out b (message/message b 2))]}
        n (net/net {} {} {} io-state)]
    (is (= io-state (net/net-io n)))
    (is (= io-state (net/net-io (net/net-with-env n {a :cell}))))
    (is (= io-state (net/net-io (net/net-with-graph n {a (graph/blank-node)}))))
    (is (= io-state (net/net-io (net/net-with-dict n {:x a}))))
    (is (= io-state (net/net-io (net/as-net n))))
    (is (= net/empty-io (net/net-io (net/as-net {:graph {} :env {} :dict {}}))))))

(deftest run-tasks-remains-compatible-with-task-queues
  (let [a (ids/new-node-id)
        b (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell a 7 7)
               (nb/install-cell b))
        [prop-id n1] ((prop/id a b) n0)
        n2 (core/run-tasks (tq/enqueue tq/empty-queue prop-id) n1)]
    (is (= 7 (strongest n2 b)))
    (is (empty? (:queue (net/net-io n2))))))

(deftest queued-local-message-merges-and-wakes-downstream
  (let [a (ids/new-node-id)
        b (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell a)
               (nb/install-cell b))
        [prop-id n1] ((prop/id a b) n0)
        n2 (-> n1
               (io/enqueue-message (message/message a 11))
               core/continue)]
    (is prop-id)
    (is (= 11 (strongest n2 a)))
    (is (= 11 (strongest n2 b)))
    (is (empty? (:queue (net/net-io n2))))
    (is (empty? (:outbox (net/net-io n2))))))

(deftest nonlocal-message-escapes-to-outbox
  (let [missing (ids/new-node-id)
        msg (message/message missing :external)
        n (core/continue (io/enqueue-message net/empty-net msg))]
    (is (= [(io/escaped-record msg)] (:outbox (net/net-io n))))))

(deftest reality-in-injects-through-ordinary-message-merge
  (let [target (ids/new-node-id)
        n0 (nb/install-cell net/empty-net target)
        [in-prop n1] ((reality/p:reality-in :stdin target) n0)
        n2 (-> n1
               (reality/inject-input :stdin target 42)
               core/continue)]
    (is in-prop)
    (is (= 42 (strongest n2 target)))
    (is (empty? (:inbox (net/net-io n2))))))

(deftest reality-out-publishes-formal-io-record
  (let [source (ids/new-node-id)
        n0 (nb/install-cell net/empty-net source 99 99)
        [out-prop n1] ((reality/p:reality-out :stdout source) n0)
        n2 (core/run-tasks (tq/enqueue tq/empty-queue out-prop) n1)]
    (is (= [(io/io-record :stdout source (message/message source 99))]
           (:outbox (net/net-io n2))))))
