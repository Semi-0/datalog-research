(ns propagators.experimental.runner-constructor-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.experimental.runner.controlled :as controlled]
            [propagators.experimental.runner.drivers :as drivers]
            [propagators.experimental.runner.examples :as examples]
            [propagators.experimental.runner.task-policy :as task-policy]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :as msg]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.scoped-address :as scoped]
            [propagators.runner-constructor :as constructor])
  (:import [java.util.concurrent Executors RejectedExecutionException TimeUnit]))

(defn- counter-policy
  []
  {:tasks-empty? zero?
   :take-task (fn [_network remaining]
                [:tick (dec remaining)])
   :add-tasks (fn [remaining _new-tasks]
                remaining)})

(defn- counter-iterator
  [limit]
  (constructor/network-iterator-constructor
   (counter-policy)
   (fn [_id network {:keys [success]}]
     (success nil network))
   (fn [_patches network {:keys [success]}]
     (success nil (update network :count inc)))))

(defn- run-experimental
  [network prop-ids]
  (examples/normal-runner
   {:network network
    :tasks (tq/enqueue-all tq/empty-queue prop-ids)}))

(defn- manual-scheduler
  []
  (let [pending* (atom clojure.lang.PersistentQueue/EMPTY)]
    {:schedule (fn [thunk]
                 (swap! pending* conj thunk)
                 :scheduled)
     :run-next! (fn []
                  (let [thunk (peek @pending*)]
                    (if thunk
                      (do
                        (swap! pending* pop)
                        (thunk)
                        true)
                      false)))
     :pending pending*}))

(defn- run-all-manual!
  [{:keys [run-next!]}]
  (loop [runs 0]
    (if (run-next!)
      (recur (inc runs))
      runs)))

(deftest iterator-selects-one-continuation
  (testing "empty tasks complete immediately"
    (let [calls* (atom [])
          advance (counter-iterator 0)]
      (advance {:network {:count 0} :tasks 0}
               {:done #(swap! calls* conj [:done %])
                :fail #(swap! calls* conj [:fail %])
                :continue #(swap! calls* conj [:continue %])})
      (is (= [[:done {:count 0}]] @calls*))))

  (testing "one task continues with updated state"
    (let [calls* (atom [])
          advance (counter-iterator 1)]
      (advance {:network {:count 0} :tasks 1}
               {:done #(swap! calls* conj [:done %])
                :fail #(swap! calls* conj [:fail %])
                :continue #(swap! calls* conj [:continue %])})
      (is (= 1 (count @calls*)))
      (is (= :continue (ffirst @calls*)))
      (is (= {:count 1} (get-in @calls* [0 1 :network])))))

  (testing "evaluator errors select fail exactly once"
    (let [calls* (atom [])
          expected (ex-info "boom" {})
          advance (constructor/network-iterator-constructor
                   (counter-policy)
                   (fn [_id _network {:keys [fail]}]
                     (fail expected))
                   (fn [_patches _network _handlers]
                     (throw (ex-info "unreachable" {}))))]
      (advance {:network {} :tasks 1}
               {:done #(swap! calls* conj [:done %])
                :fail #(swap! calls* conj [:fail %])
                :continue #(swap! calls* conj [:continue %])})
      (is (= [[:fail expected]] @calls*)))))

(deftest trampoline-runner-is-stack-safe
  (let [run (drivers/trampoline-runner (counter-iterator 100000))
        result (run {:network {:count 0} :tasks 100000})]
    (is (= :completed (:status result)))
    (is (= 100000 (get-in result [:network :count])))))

(deftest fifo-policy-preserves-order
  (let [a (ids/new-node-id)
        b (ids/new-node-id)
        tasks (tq/enqueue-all tq/empty-queue [a b])
        take-task (:take-task task-policy/fifo-task-policy)
        [first-id remaining] (take-task nil tasks)
        [second-id _] (take-task nil remaining)]
    (is (= a first-id))
    (is (= b second-id))))

(deftest ranked-round-robin-orders-rounds-and-preserves-ties
  (let [a (ids/new-node-id)
        b (ids/new-node-id)
        c (ids/new-node-id)
        scores {a 1 b 3 c 3}
        policy (task-policy/ranked-round-robin-task-policy
                (fn [_network id] (get scores id)))
        initial (task-policy/round-tasks [a b c])
        [first-id after-first] ((:take-task policy) nil initial)
        admitted ((:add-tasks policy)
                  after-first
                  (tq/enqueue tq/empty-queue a))
        [second-id after-second] ((:take-task policy) nil admitted)
        [third-id after-third] ((:take-task policy) nil after-second)
        [next-round-id final] ((:take-task policy) nil after-third)]
    (is (= [b c a a]
           [first-id second-id third-id next-round-id]))
    (is ((:tasks-empty? policy) final))))

(deftest scheduler-runner-supports-immediate-and-delayed-continuations
  (let [executor (Executors/newSingleThreadExecutor)
        delayed (Executors/newSingleThreadScheduledExecutor)
        completion (promise)
        step* (atom 0)
        advance (fn [state {:keys [done continue]}]
                  (if (zero? @step*)
                    (do
                      (swap! step* inc)
                      (.schedule delayed
                                 ^Runnable (fn [] (continue (inc state)))
                                 5
                                 TimeUnit/MILLISECONDS))
                    (done state)))
        run (drivers/scheduler-runner
             (drivers/executor-scheduler executor)
             advance)]
    (try
      (is (= :scheduled
             (run 0 {:done #(deliver completion [:done %])
                     :fail #(deliver completion [:fail %])})))
      (is (= [:done 1] (deref completion 2000 ::timeout)))
      (finally
        (.shutdownNow executor)
        (.shutdownNow delayed)))))

(deftest scheduler-runner-reports-executor-rejection
  (let [executor (Executors/newSingleThreadExecutor)
        completion (promise)
        run (drivers/scheduler-runner
             (drivers/executor-scheduler executor)
             (fn [_state _handlers]
               (throw (ex-info "unreachable" {}))))]
    (.shutdownNow executor)
    (run nil {:done #(deliver completion [:done %])
              :fail #(deliver completion [:fail %])})
    (let [[status error] (deref completion 2000 ::timeout)]
      (is (= :fail status))
      (is (instance? RejectedExecutionException error)))))

(deftest scheduler-runner-delivers-one-terminal-result
  (let [manual (manual-scheduler)
        calls* (atom [])
        run (drivers/scheduler-runner
             (:schedule manual)
             (fn [state {:keys [done fail continue]}]
               (done state)
               (fail (ex-info "late failure" {}))
               (continue (inc state))))]
    (is (= :scheduled
           (run 0 {:done #(swap! calls* conj [:done %])
                   :fail #(swap! calls* conj [:fail %])})))
    (run-all-manual! manual)
    (is (= [[:done 0]] @calls*))))

(deftest controlled-runner-pauses-forks-and-rejects-stale-continuations
  (let [{:keys [schedule run-next!] :as manual} (manual-scheduler)
        continuations* (atom [])
        delta* (atom 1)
        advance (fn [state {:keys [done continue]}]
                  (if (= 2 (:steps state))
                    (done state)
                    (let [next-state (-> state
                                         (update :steps inc)
                                         (update :value + @delta*))]
                      (swap! continuations* conj #(continue next-state)))))
        controller (controlled/controlled-scheduler-runner schedule advance)
        completed (promise)]
    ((:start! controller)
     {:steps 0 :value 0}
     {:done #(deliver completed [:done %])
      :fail #(deliver completed [:fail %])})
    (is (run-next!))
    (let [first-continuation (first @continuations*)
          paused ((:pause! controller))]
      (first-continuation)
      (is (= :paused (:mode (deref paused 2000 ::timeout))))
      (let [forked ((:back! controller) 1)
            source (:source-branch forked)
            branch (:branch forked)]
        (is (not= source branch))
        (is (= 2 (count (:branches ((:snapshot controller))))))
        (first-continuation)
        (is (= 1 (count (get-in ((:snapshot controller))
                                [:branches branch :frames]))))
        (reset! delta* 10)
        (is (= :scheduled ((:resume! controller))))
        (is (run-next!))
        ((last @continuations*))
        (is (run-next!))
        ((last @continuations*))
        (run-all-manual! manual)
        (is (= [:done {:steps 2 :value 20}]
               (deref completed 2000 ::timeout)))
        (is (= 2 (count (get-in ((:snapshot controller))
                                [:branches source :frames]))))))))

(deftest compound-object-cross-network-messages-run-with-generic-iterator
  (let [top (ids/new-node-id)
        first-id (ids/new-node-id)
        second-id (ids/new-node-id)
        leaf (ids/new-node-id)
        n0 (nb/install-cells [top first-id second-id leaf])
        [n1 tasks] (nb/install-propagator! n0 tq/empty-queue
                                           (obj/p:network-slot :first first-id top))
        [n2 tasks] (nb/install-propagator! n1 tasks
                                           (obj/p:network-slot :second second-id top))
        [n3 tasks] (nb/install-propagator! n2 tasks
                                           (obj/p:network-slot :value leaf second-id))
        [n4 tasks] (nb/seed-cell! n3 tasks leaf 9)
        result (examples/normal-runner {:network n4 :tasks tasks})
        final-network (:network result)]
    (is (= :completed (:status result)))
    (is (= 9 (net/network-cell-value final-network leaf)))
    (is (obj/accessor-network? (net/network-cell-value final-network top)))
    (is (obj/accessor-network? (net/network-cell-value final-network second-id)))))

(deftest compound-object-source-peer-and-scoped-routing-use-generic-iterator
  (testing "source slot projects from an inner value to its outer accessor"
    (let [parent (ids/new-node-id)
          coll (ids/new-node-id)
          n0 (nb/install-cells [parent coll])
          [n1 tasks] (nb/install-propagator! n0 tq/empty-queue
                                             (obj/p:network-slot :left parent coll))
          [n2 tasks] (nb/seed-cell! n1 tasks coll {:left 7})
          result (examples/normal-runner {:network n2 :tasks tasks})]
      (is (= 7 (net/network-cell-value (:network result) parent)))))

  (testing "an outer parent update refines peers without rewriting the collection"
    (let [p1 (ids/new-node-id)
          p2 (ids/new-node-id)
          coll (ids/new-node-id)
          n0 (nb/install-cells [p1 p2 coll])
          [n1 tasks] (nb/install-propagator! n0 tq/empty-queue
                                             (obj/p:network-slot :x p1 coll))
          [n2 tasks] (nb/install-propagator! n1 tasks
                                             (obj/p:network-slot :x p2 coll))
          settled (:network (examples/normal-runner {:network n2 :tasks tasks}))
          coll-before (net/network-cell-value settled coll)
          [seeded tasks] (nb/seed-cell! settled tq/empty-queue p2 10)
          final-network (:network (examples/normal-runner
                                   {:network seeded :tasks tasks}))]
      (is (= 10 (net/network-cell-value final-network p1)))
      (is (= 10 (net/network-cell-value final-network p2)))
      (is (= coll-before (net/network-cell-value final-network coll)))))

  (testing "scoped participants route through the parent dictionary"
    (let [parent (ids/new-node-id)
          scoped-target (ids/new-node-id)
          coll (ids/new-node-id)
          child-local (ids/new-node-id)
          child-ref (scoped/cell-ref [:experimental/scope] child-local)
          n0 (nb/install-cells [parent scoped-target coll])
          [n1 tasks] (nb/install-propagator! n0 tq/empty-queue
                                             (obj/p:network-slot :x parent coll))
          routed (net/assoc-net-dict-entry n1 child-ref
                                           [:dispatch/local scoped-target])
          settled (:network (examples/normal-runner {:network routed :tasks tasks}))
          [declaration-tasks declared]
          (core/eval-cell coll
                          (msg/message coll (obj/accessor-declaration :x child-ref))
                          settled)
          declared* (:network (examples/normal-runner
                               {:network declared :tasks declaration-tasks}))
          coll-before (net/network-cell-value declared* coll)
          [seeded seed-tasks] (nb/seed-cell! declared* tq/empty-queue parent 10)
          final-network (:network (examples/normal-runner
                                   {:network seeded :tasks seed-tasks}))]
      (is (= 10 (net/network-cell-value final-network scoped-target)))
      (is (= coll-before (net/network-cell-value final-network coll)))
      (is (not (contains? (net/net-env final-network) child-ref))))))
