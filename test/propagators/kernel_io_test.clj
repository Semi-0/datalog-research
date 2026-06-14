(ns propagators.kernel-io-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.core :as core]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.io :as io]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as propagator]
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
                  :outbox [(io/io-record :out b (message/message b 2))]
                  :lexical-envs {}}
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

(defn- child-forward-net
  [parent-out-id]
  (let [in (ids/new-node-id)
        n0 (nb/install-cell net/empty-net in)
        [prop-id n1]
        ((propagator/construct-propagator
          (fn [_inputs _outputs n]
            (let [v (net/network-cell-strongest n in)]
              (if (value/unusable? v)
                []
                [(message/message parent-out-id v)])))
          [in]
          [])
         n0)]
    {:net n1
     :in in
     :props [prop-id]}))

(deftest lexical-cell-ref-dispatch-runs-subenv-and-exports-outbox
  (let [scope [:child]
        parent-out (ids/new-node-id)
        {child :net child-in :in} (child-forward-net parent-out)
        n0 (-> net/empty-net
               (nb/install-cell parent-out)
               (io/assoc-lexical-env scope child))
        n1 (-> n0
               (io/enqueue-message
                (message/message (io/cell-ref scope child-in) 7))
               core/continue)
        child1 (io/lexical-env n1 scope)]
    (is (= 7 (strongest n1 parent-out)))
    (is (= 7 (net/network-cell-strongest child1 child-in)))
    (is (empty? (:outbox (net/net-io n1))))))

(defn- compound-slot-keys
  [source-net]
  (if-let [count-value (obj/slot-value source-net :count)]
    (vec (range count-value))
    (->> (obj/public-slot-keys source-net)
         (remove #{:count})
         (sort-by pr-str)
         vec)))

(declare lexical-walk-frame)

(defn- register-child-frame
  [scope source-net slot-key]
  (let [child-scope (conj scope slot-key)
        child-net (lexical-walk-frame child-scope)
        slot-value (obj/slot-value source-net slot-key)]
    [(io/io-delivery :assoc-lexical-env [child-scope child-net])
     (message/message (io/name-ref child-scope :source) slot-value)]))

(defn- lexical-walk-frame
  [scope]
  (let [source-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell source-id)
               (net/assoc-net-dict-entry :source source-id))
        [prop-id n1]
        ((propagator/construct-propagator
          (fn [_inputs _outputs n]
            (let [source-value (net/network-cell-strongest n source-id)
                  source-net (obj/compound-object source-value)]
              (cond
                (value/unusable? source-value)
                []

                (not (value/contradiction? source-net))
                (vec (mapcat #(register-child-frame scope source-net %)
                             (compound-slot-keys source-net)))

                :else
                [])))
          [source-id]
          [])
         n0)]
    (net/update-net-dict-entry n1 :frame/props (fnil conj []) prop-id)))

(defn- lexical-source-value
  [n scope]
  (let [frame (io/lexical-env n scope)
        source-id (net/network-dict-entry frame :source)]
    (net/network-cell-strongest frame source-id)))

(deftest lexical-name-ref-supports-dynamic-recursion-over-nested-compound
  (let [root-scope []
        source {:left [0 1 2]
                :right {:a 3
                        :b [4 5]
                        :empty []}}
        root-frame (lexical-walk-frame root-scope)
        n0 (-> net/empty-net
               (io/assoc-lexical-env root-scope root-frame))
        n1 (-> n0
               (io/enqueue-message
                (message/message (io/name-ref root-scope :source) source))
               core/continue)]
    (is (= source (lexical-source-value n1 root-scope)))
    (is (= 0 (lexical-source-value n1 [:left 0])))
    (is (= 1 (lexical-source-value n1 [:left 1])))
    (is (= 2 (lexical-source-value n1 [:left 2])))
    (is (= 3 (lexical-source-value n1 [:right :a])))
    (is (= 4 (lexical-source-value n1 [:right :b 0])))
    (is (= 5 (lexical-source-value n1 [:right :b 1])))
    (is (= [] (lexical-source-value n1 [:right :empty])))
    (is (contains? (io/lexical-envs n1) [:right :b]))))
