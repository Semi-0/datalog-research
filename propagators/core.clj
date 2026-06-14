(ns propagators.core
  "Propagation scheduler (eval cells/propagators, run task queue)."
  (:require [clojure.core.match :refer [match]]
            [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.io :as io]
            [propagators.message :refer [message message-id message-value]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.runtime :as runtime]))

(defn eval-cell [id msg n]
  (let [merge-net (io/clear-activation-state n)
        old (net/env-get (net/net-env n) id)
        old-strongest (merge/strongest-value old merge-net)
        content' (merge/cell-merge (cell/cell-content old) (message-value msg) merge-net)
        strongest' (merge/strongest-value content' merge-net)
        n' (net/assoc-net-cell n id (cell/cell content' strongest'))
        node (graph/get-node (net/net-graph n') id)
        next-tasks (tq/enqueue-all tq/empty-queue (graph/node-output-ids node))]
    (if (merge/cell-updated? strongest' old-strongest merge-net)
      (if (value/contradiction? strongest')
        (let [[tasks env] (merge/handle-contradiction next-tasks id (net/net-env n'))]
          [tasks (net/net-with-env n' env)])
        [next-tasks n'])
      [tq/empty-queue n])))

(declare continue)

(defn- eval-lexical-cell [ref msg n]
  (let [scope (io/ref-scope ref)
        child-net (io/lexical-env n scope)]
    (if-not (net/network? child-net)
      [tq/empty-queue
       (io/append-outbox n (io/escaped-record msg))]
      (if-let [cell-id (io/resolve-ref-cell child-net ref)]
        (let [child0 (-> child-net
                         (io/with-lexical-envs (io/lexical-envs n))
                         (io/enqueue-message (message cell-id
                                                      (message-value msg))))
              child1 (continue child0)
              [records child2] (io/drain-outbox child1)
              child-envs (assoc (io/lexical-envs child2)
                                scope
                                (io/stored-lexical-env child2))
              n' (io/with-lexical-envs n child-envs)]
          [tq/empty-queue
           (io/enqueue-deliveries n' (mapv :message records))])
        [tq/empty-queue
         (io/append-outbox n (io/escaped-record msg))]))))

(defn eval-cells [messages n]
  (loop [ms messages
         tasks tq/empty-queue
         n' n]
    (if (empty? ms)
      [tasks n']
      (let [msg (first ms)
            id (message-id msg)
            [poped new-n] (if (contains? (net/net-env n') id)
                            (eval-cell id msg n')
                            (if (io/lexical-ref? id)
                              (eval-lexical-cell id msg n')
                              [tq/empty-queue
                               (io/append-outbox n' (io/escaped-record msg))]))]
        (recur (rest ms) (tq/merge-queues tasks poped) new-n)))))

(declare eval-propagator)

(defn eval-delivery [delivery n]
  (match [(io/normalize-delivery delivery)]
    [[:message msg]]
    (eval-cells [msg] n)

    [[:prop prop-id]]
    (eval-propagator prop-id tq/empty-queue n)

    [[:io op payload]]
    [tq/empty-queue (io/apply-io-delivery n op payload)]))

(defn eval-deliveries [deliveries n]
  (loop [xs deliveries
         tasks tq/empty-queue
         n' n]
    (if (empty? xs)
      [tasks n']
      (let [[poped new-n] (eval-delivery (first xs) n')]
        (recur (rest xs) (tq/merge-queues tasks poped) new-n)))))

(defn eval-propagator [current-id tasks n]
  (let [g (net/net-graph n)
        e (net/net-env n)
        current-node (graph/get-node g current-id)
        inputs (graph/node-input-ids current-node)
        outputs (graph/node-output-ids current-node)
        f (prop/prop-f (net/env-get e current-id))
        activation-net (io/clear-activation-state n)
        deliveries (binding [runtime/*continue* continue]
                     (f inputs outputs activation-net))
        [poped new-net] (eval-deliveries deliveries n)]
    [(tq/merge-queues tasks poped) new-net]))

(defn continue [n]
  (loop [n' n]
    (if-let [[delivery n''] (io/pop-delivery n')]
      (let [[tasks new-net] (eval-delivery delivery n'')]
        (recur (io/enqueue-props new-net tasks)))
      n')))

(defn run-tasks [tasks n]
  (continue (io/enqueue-props n tasks)))
