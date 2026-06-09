(ns propagators.behavior-arithmetic-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell-protocol :as protocol]
            [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.core :as core]
            [propagators.datastructures.behavior :as behavior]
            [propagators.datastructures.behavior-algebra :as hist]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :refer [new-node-id]]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.stdlib.arithmetic.behavior :as behavior-arithmetic]))

(defn- install-protocols
  [n]
  (-> n
      (compile/install-and-run (protocol/install-cell-protocol))
      (compile/install-and-run (protocol/install-behavior-protocol))))

(defn- behavior-net
  []
  (install-protocols net/empty-net))

(defn- behavior-view
  [records source-keys]
  (behavior/behavior-value
   {:history (hist/records->history records)
    :source-keys source-keys
    :reducer behavior/event-history-reducer-id}))

(defn- install-behavior-cell
  [n id view]
  (nb/install-cell n id view (behavior/strongest-value view)))

(defn- record-map
  [record]
  (cond
    (hist/point-record? record)
    {:at (obj/slot-value record :at)
     :value (obj/slot-value record :value)}

    (hist/interval-record? record)
    {:from (obj/slot-value record :from)
     :to (obj/slot-value record :to)
     :value (obj/slot-value record :value)}))

(defn- records
  [v]
  (mapv record-map (behavior/history-records v)))

(defn- current-value
  [n id]
  (let [strongest (net/network-cell-strongest n id)]
    (if (value/unusable? strongest)
      strongest
      (behavior/base-value strongest))))

(defn- seed-behavior-message
  [n id view]
  (let [[tasks n1] (core/eval-cell id (message id view) n)]
    [tasks n1]))

(deftest behavior-plus-merges-point-values-at-the-same-timestamp
  (testing "behavior + joins point histories by exact tick"
    (let [a (new-node-id)
          b (new-node-id)
          out (new-node-id)
          left (behavior-view [(hist/point-record 6 2)] #{[:a 6]})
          right (behavior-view [(hist/point-record 6 7)] #{[:b 6]})
          n0 (-> (behavior-net)
                 (install-behavior-cell a left)
                 (install-behavior-cell b right)
                 (nb/install-cell out))
          [prop-id n1] ((behavior-arithmetic/+ a b out) n0)
          result (nb/run-propagators n1 [prop-id])
          out-content (net/network-cell-content result out)]
      (is (= 9 (current-value result out)))
      (is (= [{:at 6 :value 9}]
             (records out-content)))
      (is (= #{[0 [:a 6]]
               [1 [:b 6]]}
             (behavior/source-keys out-content))))))

(deftest behavior-plus-joins-all-input-arguments
  (testing "variadic behavior + synchronizes every retained input history"
    (let [a (new-node-id)
          b (new-node-id)
          c (new-node-id)
          out (new-node-id)
          first-view (behavior-view [(hist/point-record 6 2)
                                     (hist/point-record 7 20)]
                                    #{[:a 6] [:a 7]})
          second-view (behavior-view [(hist/point-record 6 7)]
                                     #{[:b 6]})
          third-view (behavior-view [(hist/point-record 6 10)
                                    (hist/point-record 8 100)]
                                   #{[:c 6] [:c 8]})
          n0 (-> (behavior-net)
                 (install-behavior-cell a first-view)
                 (install-behavior-cell b second-view)
                 (install-behavior-cell c third-view)
                 (nb/install-cell out))
          [prop-id n1] ((behavior-arithmetic/+ a b c out) n0)
          result (nb/run-propagators n1 [prop-id])
          out-content (net/network-cell-content result out)]
      (is (= 19 (current-value result out)))
      (is (= [{:at 6 :value 19}]
             (records out-content)))
      (is (= #{[0 [:a 6]]
               [0 [:a 7]]
               [1 [:b 6]]
               [2 [:c 6]]
               [2 [:c 8]]}
             (behavior/source-keys out-content))))))

(deftest behavior-plus-does-not-treat-points-as-continuing
  (testing "different point ticks do not synchronize"
    (let [a (new-node-id)
          b (new-node-id)
          out (new-node-id)
          left (behavior-view [(hist/point-record 6 2)] #{[:a 6]})
          right (behavior-view [(hist/point-record 7 7)] #{[:b 7]})
          n0 (-> (behavior-net)
                 (install-behavior-cell a left)
                 (install-behavior-cell b right)
                 (nb/install-cell out))
          [prop-id n1] ((behavior-arithmetic/+ a b out) n0)
          result (nb/run-propagators n1 [prop-id])]
      (is (= value/nothing (net/network-cell-strongest result out))))))

(deftest behavior-plus-synchronizes-interval-overlap
  (testing "interval arithmetic emits only the overlapped interval"
    (let [a (new-node-id)
          b (new-node-id)
          out (new-node-id)
          left (behavior-view [(hist/interval-record 0 10 2)] #{[:a 0]})
          right (behavior-view [(hist/interval-record 5 12 7)] #{[:b 5]})
          n0 (-> (behavior-net)
                 (install-behavior-cell a left)
                 (install-behavior-cell b right)
                 (nb/install-cell out))
          [prop-id n1] ((behavior-arithmetic/+ a b out) n0)
          result (nb/run-propagators n1 [prop-id])
          out-content (net/network-cell-content result out)]
      (is (= 9 (current-value result out)))
      (is (= [{:from 5 :to 10 :value 9}]
             (records out-content))))))

(deftest behavior-arithmetic-reacts-to-later-same-timestamp-merge
  (testing "later retained point evidence merges values at the newly shared tick"
    (let [a (new-node-id)
          b (new-node-id)
          out (new-node-id)
          left-6 (behavior-view [(hist/point-record 6 2)] #{[:a 6]})
          right-6 (behavior-view [(hist/point-record 6 7)] #{[:b 6]})
          left-6-8 (behavior-view [(hist/point-record 6 2)
                                   (hist/point-record 8 3)]
                                  #{[:a 6] [:a 8]})
          right-6-8 (behavior-view [(hist/point-record 6 7)
                                    (hist/point-record 8 10)]
                                   #{[:b 6] [:b 8]})
          n0 (-> (behavior-net)
                 (install-behavior-cell a left-6)
                 (install-behavior-cell b right-6)
                 (nb/install-cell out))
          [prop-id n1] ((behavior-arithmetic/+ a b out) n0)
          n2 (nb/run-propagators n1 [prop-id])
          [_left-tasks n3] (seed-behavior-message n2 a left-6-8)
          [right-tasks n4] (seed-behavior-message n3 b right-6-8)
          result (core/run-tasks right-tasks n4)
          out-content (net/network-cell-content result out)]
      (is (= 13 (current-value result out)))
      (is (= [{:at 6 :value 9}
              {:at 8 :value 13}]
             (records out-content))))))

(deftest behavior-negation-and-minus-use-retained-history
  (testing "unary negation and binary subtraction preserve synchronized times"
    (let [a (new-node-id)
          b (new-node-id)
          neg-out (new-node-id)
          minus-out (new-node-id)
          left (behavior-view [(hist/point-record 6 10)] #{[:a 6]})
          right (behavior-view [(hist/point-record 6 4)] #{[:b 6]})
          n0 (-> (behavior-net)
                 (install-behavior-cell a left)
                 (install-behavior-cell b right)
                 (nb/install-cell neg-out)
                 (nb/install-cell minus-out))
          [neg-prop n1] ((behavior-arithmetic/negate b neg-out) n0)
          [minus-prop n2] ((behavior-arithmetic/- a b minus-out) n1)
          result (nb/run-propagators n2 [neg-prop minus-prop])]
      (is (= -4 (current-value result neg-out)))
      (is (= [{:at 6 :value -4}]
             (records (net/network-cell-content result neg-out))))
      (is (= 6 (current-value result minus-out)))
      (is (= [{:at 6 :value 6}]
             (records (net/network-cell-content result minus-out)))))))
