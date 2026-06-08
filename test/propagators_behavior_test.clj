(ns propagators-behavior-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.datastructures.behavior :as behavior]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.cells.cell-protocol :as protocol]))

(defn- install-protocol
  [n]
  (compile/install-and-run n (protocol/install-cell-protocol)))

(defn- install-behavior
  [n]
  (compile/install-and-run n (protocol/install-behavior-protocol)))

(defn- behavior-net
  []
  (install-behavior (install-protocol net/empty-net)))

(defn- strongest
  [n id]
  (net/network-cell-strongest n id))

(defn- content
  [n id]
  (net/network-cell-content n id))

(defn- current-value
  [n id]
  (let [v (strongest n id)]
    (if (value/unusable? v)
      v
      (behavior/base-value v))))

(defn- install-behavior-reducer
  [n history-id merge-id init-id out-id reducer-net]
  (let [n0 (-> n
               (nb/ensure-cell history-id)
               (nb/ensure-cell merge-id)
               (nb/ensure-cell init-id)
               (nb/ensure-cell out-id)
               (nb/seed-cell merge-id reducer-net)
               (nb/seed-cell init-id (behavior/empty-history-state)))
        [props n1] ((behavior/p:behavior history-id merge-id init-id out-id) n0)]
    {:net n1 :props props}))

(defn- install-event
  [n history-id tick value]
  (let [value-id (new-node-id)
        n0 (-> n
               (nb/ensure-cell value-id)
               (nb/seed-cell value-id value))
        [prop-id n1] ((behavior/p:event tick value-id history-id) n0)]
    {:net n1 :prop prop-id :value-id value-id}))

(defn- run
  [n props]
  (nb/run-propagators n props))

(defn- behavior-slots
  [v]
  {:history (behavior/history-records v)
   :source-keys (behavior/source-keys v)
   :reducer (behavior/reducer-id v)})

(defn- record-map
  [record]
  (if (some? (obj/slot-value record :at))
    {:at (obj/slot-value record :at)
     :value (obj/slot-value record :value)}
    {:from (obj/slot-value record :from)
     :to (obj/slot-value record :to)
     :value (obj/slot-value record :value)}))

(defn- records
  [v]
  (mapv record-map (behavior/history-records v)))

(defn- interval-map
  [interval]
  {:from (obj/slot-value interval :from)
   :to (obj/slot-value interval :to)})

(deftest behavior-history-state-is-slot-addressable
  (testing "reducer accumulator state is a compound-object layered value"
    (let [state (behavior/history-state
                 {6 :x}
                 {6 (behavior/point-event 6 :x)})
          events (obj/slot-value state behavior/state-events-layer)
          history (obj/slot-value state behavior/state-history-layer)]
      (is (= #{behavior/state-events-layer behavior/state-history-layer}
             (obj/public-slot-keys state)))
      (is (= #{6} (obj/public-slot-keys events)))
      (is (= #{6} (obj/public-slot-keys history)))
      (is (= :x (obj/slot-value events 6)))
      (is (= {:at 6 :value :x}
             (record-map (obj/slot-value history 6)))))))

(deftest behavior-event-before-reducer-install-computes-point-history
  (testing "an existing sparse event is folded when p:behavior is activated"
    (let [history-id (new-node-id)
          merge-id (new-node-id)
          init-id (new-node-id)
          out-id (new-node-id)
          event (install-event (behavior-net) history-id 6 :x)
          after-event (run (:net event) [(:prop event)])
          reducer (install-behavior-reducer after-event
                                            history-id
                                            merge-id
                                            init-id
                                            out-id
                                            (behavior/event-history-reducer-net))
          result (run (:net reducer) (:props reducer))
          out-content (content result out-id)]
      (is (= :x (current-value result out-id)))
      (is (= 6 (behavior/summary-latest-time (strongest result out-id))))
      (is (= 1 (behavior/summary-retained-count (strongest result out-id))))
      (is (= {:from 6 :to 6}
             (interval-map
              (behavior/summary-retained-interval (strongest result out-id)))))
      (is (= #{6} (obj/public-slot-keys out-content)))
      (is (= {:history [{:at 6 :value :x}]
              :source-keys #{6}
              :reducer behavior/event-history-reducer-id}
             (update (behavior-slots out-content) :history #(mapv record-map %)))))))

(deftest behavior-reducer-before-later-event-updates-through-slot-topology
  (testing "later p:event activation wakes the reducer through the source cell"
    (let [history-id (new-node-id)
          merge-id (new-node-id)
          init-id (new-node-id)
          out-id (new-node-id)
          reducer (install-behavior-reducer (behavior-net)
                                            history-id
                                            merge-id
                                            init-id
                                            out-id
                                            (behavior/event-history-reducer-net))
          empty-result (run (:net reducer) (:props reducer))
          event (install-event empty-result history-id 7 :later)
          result (run (:net event) [(:prop event)])
          out-content (content result out-id)]
      (is (= value/nothing (strongest empty-result out-id)))
      (is (= [] (records (content empty-result out-id))))
      (is (= :later (current-value result out-id)))
      (is (= {:history [{:at 7 :value :later}]
              :source-keys #{7}
              :reducer behavior/event-history-reducer-id}
             (update (behavior-slots out-content) :history #(mapv record-map %)))))))

(deftest behavior-late-out-of-order-event-reorders-retained-history
  (testing "source keys grow monotonically while the retained point history is sorted"
    (let [history-id (new-node-id)
          merge-id (new-node-id)
          init-id (new-node-id)
          out-id (new-node-id)
          reducer (install-behavior-reducer (behavior-net)
                                            history-id
                                            merge-id
                                            init-id
                                            out-id
                                            (behavior/event-history-reducer-net))
          event-10 (install-event (:net reducer) history-id 10 :a)
          n1 (run (:net event-10) (conj (:props reducer) (:prop event-10)))
          event-30 (install-event n1 history-id 30 :c)
          n2 (run (:net event-30) [(:prop event-30)])
          event-20 (install-event n2 history-id 20 :b)
          result (run (:net event-20) [(:prop event-20)])
          out-content (content result out-id)]
      (is (= :c (current-value result out-id)))
      (is (= 30 (behavior/summary-latest-time (strongest result out-id))))
      (is (= 3 (behavior/summary-retained-count (strongest result out-id))))
      (is (= {:from 10 :to 30}
             (interval-map
              (behavior/summary-retained-interval (strongest result out-id)))))
      (is (= [{:at 10 :value :a}
              {:at 20 :value :b}
              {:at 30 :value :c}]
             (records out-content)))
      (is (= #{10 20 30} (behavior/source-keys out-content))))))

(deftest behavior-duplicate-and-conflicting-same-tick-events
  (testing "same tick/same value is idempotent"
    (let [history-id (new-node-id)
          merge-id (new-node-id)
          init-id (new-node-id)
          out-id (new-node-id)
          reducer (install-behavior-reducer (behavior-net)
                                            history-id
                                            merge-id
                                            init-id
                                            out-id
                                            (behavior/event-history-reducer-net))
          left (install-event (:net reducer) history-id 6 :same)
          n1 (run (:net left) (conj (:props reducer) (:prop left)))
          right (install-event n1 history-id 6 :same)
          result (run (:net right) [(:prop right)])]
      (is (= :same (current-value result out-id)))
      (is (= [{:at 6 :value :same}]
             (records (content result out-id))))))

  (testing "same tick/different value contradicts through normal slot merge"
    (let [history-id (new-node-id)
          merge-id (new-node-id)
          init-id (new-node-id)
          out-id (new-node-id)
          reducer (install-behavior-reducer (behavior-net)
                                            history-id
                                            merge-id
                                            init-id
                                            out-id
                                            (behavior/event-history-reducer-net))
          left (install-event (:net reducer) history-id 6 :left)
          n1 (run (:net left) (conj (:props reducer) (:prop left)))
          right (install-event n1 history-id 6 :right)
          result (run (:net right) [(:prop right)])
          source (strongest result history-id)]
      (is (= value/contradiction
             (obj/slot-value source (behavior/event-slot-key 6)))))))

(deftest behavior-history-reducers-are-explicit-about-continuation
  (testing "event history emits point records and does not imply infinity"
    (let [history-id (new-node-id)
          merge-id (new-node-id)
          init-id (new-node-id)
          out-id (new-node-id)
          reducer (install-behavior-reducer (behavior-net)
                                            history-id
                                            merge-id
                                            init-id
                                            out-id
                                            (behavior/event-history-reducer-net))
          event (install-event (:net reducer) history-id 6 :x)
          result (run (:net event) (conj (:props reducer) (:prop event)))]
      (is (= :x (current-value result out-id)))
      (is (= [{:at 6 :value :x}]
             (records (content result out-id))))))

  (testing "constant history explicitly emits open intervals"
    (let [history-id (new-node-id)
          merge-id (new-node-id)
          init-id (new-node-id)
          out-id (new-node-id)
          reducer (install-behavior-reducer (behavior-net)
                                            history-id
                                            merge-id
                                            init-id
                                            out-id
                                            (behavior/constant-history-reducer-net))
          event-6 (install-event (:net reducer) history-id 6 :x)
          result (run (:net event-6) (conj (:props reducer) (:prop event-6)))]
      (is (= :x (current-value result out-id)))
      (is (= [{:from 0 :to 6 :value value/nothing}
              {:from 6 :to :infinity :value :x}]
             (records (content result out-id)))))))

(deftest behavior-window-reducer-retains-last-n-point-records
  (testing "window retention is exactly the reducer-emitted history view"
    (let [history-id (new-node-id)
          merge-id (new-node-id)
          init-id (new-node-id)
          out-id (new-node-id)
          reducer (install-behavior-reducer (behavior-net)
                                            history-id
                                            merge-id
                                            init-id
                                            out-id
                                            (behavior/window-history-reducer-net 2))
          event-1 (install-event (:net reducer) history-id 1 :a)
          n1 (run (:net event-1) (conj (:props reducer) (:prop event-1)))
          event-2 (install-event n1 history-id 2 :b)
          n2 (run (:net event-2) [(:prop event-2)])
          event-3 (install-event n2 history-id 3 :c)
          result (run (:net event-3) [(:prop event-3)])
          out-content (content result out-id)]
      (is (= :c (current-value result out-id)))
      (is (= 3 (behavior/summary-latest-time (strongest result out-id))))
      (is (= 2 (behavior/summary-retained-count (strongest result out-id))))
      (is (= {:from 2 :to 3}
             (interval-map
              (behavior/summary-retained-interval (strongest result out-id)))))
      (is (= [{:at 2 :value :b}
              {:at 3 :value :c}]
             (records out-content)))
      (is (= #{1 2 3} (behavior/source-keys out-content))))))

(deftest behavior-protocol-merges-by-reducer-and-source-evidence
  (testing "source-key supersets replace older behavior views"
    (let [n (behavior-net)
          old (behavior/behavior-value
               {:history {1 (behavior/point-event 1 :a)}
                :source-keys #{1}
                :reducer behavior/event-history-reducer-id})
          new (behavior/behavior-value
               {:history {1 (behavior/point-event 1 :a)
                          2 (behavior/point-event 2 :b)}
                :source-keys #{1 2}
                :reducer behavior/event-history-reducer-id})]
      (is (= (behavior-slots new)
             (behavior-slots (merge/cell-merge old new n))))
      (is (= (behavior-slots new)
             (behavior-slots (merge/cell-merge new old n))))
      (is (= :b
             (behavior/base-value
              (behavior/strongest-value (merge/cell-merge old new n)))))
      (is (= 2
             (behavior/summary-latest-time
              (behavior/strongest-value (merge/cell-merge old new n)))))
      (is (= {:from 1 :to 2}
             (interval-map
              (behavior/summary-retained-interval
               (behavior/strongest-value (merge/cell-merge old new n))))))))

  (testing "equal source keys with unequal history contradict"
    (let [n (behavior-net)
          left (behavior/behavior-value
                {:history {1 (behavior/point-event 1 :a)}
                 :source-keys #{1}
                 :reducer behavior/event-history-reducer-id})
          right (behavior/behavior-value
                 {:history {1 (behavior/point-event 1 :b)}
                  :source-keys #{1}
                  :reducer behavior/event-history-reducer-id})]
      (is (= value/contradiction (merge/cell-merge left right n)))))

  (testing "different reducers contradict in one behavior output cell"
    (let [n (behavior-net)
          event-view (behavior/behavior-value
                      {:history {1 (behavior/point-event 1 :a)}
                       :source-keys #{1}
                       :reducer behavior/event-history-reducer-id})
          constant-view (behavior/behavior-value
                         {:history {1 (behavior/constant-interval 1 :a)}
                          :source-keys #{1}
                          :reducer behavior/constant-history-reducer-id})]
      (is (= value/contradiction
             (merge/cell-merge event-view constant-view n))))))
