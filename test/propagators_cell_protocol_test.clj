(ns propagators-cell-protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell]
            [propagators.cells.cell-protocol :as protocol]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.intensity :as intensity]
            [propagators.generic-procedure :as generic]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.stdlib.provenance-arithmetic :as prov-arith]
            [propagators.stdlib.prop :as stdlib-prop]))

(defn- install-protocol
  [n]
  (compile/install-and-run n (protocol/install-cell-protocol)))

(defn- install-intensity
  [n]
  (compile/install-and-run n (protocol/install-intensity-protocol)))

(defn- protocol-net
  []
  (install-protocol net/empty-net))

(defn- intensity-net
  []
  (install-intensity (protocol-net)))

(defn- define-merge-handler
  [n applicability handler]
  (compile/install-and-run
   n
   (protocol/define-merge-handler applicability handler)))

(defn- define-strongest-handler
  [n applicability handler]
  (compile/install-and-run
   n
   (protocol/define-strongest-handler applicability handler)))

(defn- intensity-update
  [intensity payload]
  (intensity/intensity-value intensity payload))

(defn- merge-into
  [n content intensity payload]
  (merge/cell-merge content (intensity-update intensity payload) n))

(defn- queued?
  [tasks prop-id]
  (contains? (:task-queue/seen (tq/into-queue tasks)) prop-id))

(defn- selected-base
  [v]
  (obj/slot-value v :base))

(defn- selected-intensity
  [v]
  (obj/slot-value v :intensity))

(deftest protocol-falls-back-to-built-in-merge-and-strongest
  (testing "without protocol generics, built-in behavior is unchanged"
    (is (= :x (merge/cell-merge value/nothing :x net/empty-net)))
    (is (= :x (merge/strongest-value :x net/empty-net))))

  (testing "installed but unextended protocol generics still fall back"
    (let [n (protocol-net)]
      (is (= :x (merge/cell-merge value/nothing :x n)))
      (is (= :x (merge/strongest-value :x n))))))

(deftest protocol-generics-are-extendable-through-generic-handlers
  (testing "merge and strongest can be extended by network-local generic cells"
    (let [n0 (protocol-net)
          n1 (define-merge-handler
              n0
              (generic/match-cells-pred #(= :left %) #(= :right %))
              (generic/handler-closure (fn [_content _update] :merged)))
          n2 (define-strongest-handler
              n1
              (generic/match-cells-pred vector?)
              (generic/handler-closure first))]
      (is (= :merged (merge/cell-merge :left :right n2)))
      (is (= :a (merge/strongest-value [:a :b] n2))))))

(deftest intensity-protocol-merges-and-selects-highest-intensity
  (testing "nothing plus one tagged update keeps a layered intensity value"
    (let [n (intensity-net)
          content (merge-into n value/nothing 1 :a)]
      (is (intensity/intensity-value? content))
      (is (= :a (selected-base content)))
      (is (= 1 (selected-intensity content)))
      (is (= :a (selected-base (merge/strongest-value content n))))))

  (testing "higher intensity replaces lower strongest"
    (let [n (intensity-net)
          low (merge-into n value/nothing 1 :low)
          high (merge-into n low 10 :high)]
      (is (= :high (selected-base (merge/strongest-value high n))))
      (is (= 10 (selected-intensity (merge/strongest-value high n))))))

  (testing "lower intensity after higher does not replace strongest"
    (let [n (intensity-net)
          high (merge-into n value/nothing 10 :high)
          low (merge-into n high 1 :low)]
      (is (= :high (selected-base (merge/strongest-value low n))))
      (is (= 10 (selected-intensity (merge/strongest-value low n))))))

  (testing "same intensity same value is idempotent"
    (let [n (intensity-net)
          first-content (merge-into n value/nothing 5 :same)
          second-content (merge-into n first-content 5 :same)]
      (is (= :same (selected-base second-content)))
      (is (= :same (selected-base (merge/strongest-value second-content n))))))

  (testing "same intensity conflicting value contradicts when selected"
    (let [n (intensity-net)
          first-content (merge-into n value/nothing 5 :left)
          second-content (merge-into n first-content 5 :right)]
      (is (intensity/intensity-content? second-content))
      (is (= value/contradiction (merge/strongest-value second-content n))))))

(deftest with-intensity-builds-compound-update-from-cells
  (testing "p:with-intensity writes :intensity and :base layers"
    (let [intensity-id (new-node-id)
          value-id (new-node-id)
          out-id (new-node-id)
          n0 (-> (nb/install-cells [intensity-id value-id out-id])
                 (nb/seed-cell intensity-id 3)
                 (nb/seed-cell value-id :payload))
          [props n1] ((intensity/p:with-intensity intensity-id value-id out-id) n0)
          n2 (nb/run-propagators n1 props)
          tagged (net/network-cell-strongest n2 out-id)]
      (is (= 3 (obj/slot-value tagged :intensity)))
      (is (= :payload (obj/slot-value tagged :base))))))

(deftest layered-arithmetic-adds-intensity-layers
  (testing "layered arithmetic sums argument intensity like provenance unions sets"
    (let [{:keys [net operator]} (prov-arith/+ net/empty-net
                                               {:provenance? false
                                                :intensity? true})
          a (new-node-id)
          b (new-node-id)
          out (new-node-id)
          a-base (new-node-id)
          a-intensity (new-node-id)
          b-base (new-node-id)
          b-intensity (new-node-id)
          n0 (reduce (fn [acc id] (nb/install-cell acc id))
                     net
                     [a b out a-base a-intensity b-base b-intensity])
          [a-props n1] ((intensity/p:with-intensity a-intensity a-base a) n0)
          [b-props n2] ((intensity/p:with-intensity b-intensity b-base b) n1)
          [apply-prop n3] ((operator a b out) n2)
          n4 (-> n3
                 (nb/seed-cell a-base 10)
                 (nb/seed-cell a-intensity 2)
                 (nb/seed-cell b-base 20)
                 (nb/seed-cell b-intensity 3)
                 (nb/run-propagators (into a-props b-props))
                 (nb/run-propagators [apply-prop]))
          out-object (net/network-cell-strongest n4 out)]
      (is (= 30 (obj/slot-value out-object :base)))
      (is (= 5 (obj/slot-value out-object :intensity))))))

(deftest scheduler-wakes-only-when-protocol-strongest-changes
  (testing "higher intensity update wakes downstream"
    (let [source-id (new-node-id)
          out-id (new-node-id)
          n0 (-> (intensity-net)
                 (nb/install-cell source-id)
                 (nb/install-cell out-id))
          [sync-prop n1] ((stdlib-prop/id source-id out-id) n0)
          [tasks1 n2] (core/eval-cell source-id
                                       (message source-id (intensity-update 1 :low))
                                       n1)
          n3 (core/run-tasks tasks1 n2)
          [tasks2 n4] (core/eval-cell source-id
                                       (message source-id (intensity-update 10 :high))
                                       n3)]
      (is (= :low (selected-base (net/network-cell-strongest n3 out-id))))
      (is (queued? tasks2 sync-prop))
      (is (= :high (selected-base (net/network-cell-strongest n4 source-id))))))

  (testing "lower intensity update does not wake downstream"
    (let [source-id (new-node-id)
          out-id (new-node-id)
          n0 (-> (intensity-net)
                 (nb/install-cell source-id)
                 (nb/install-cell out-id))
          [_sync-prop n1] ((stdlib-prop/id source-id out-id) n0)
          [tasks1 n2] (core/eval-cell source-id
                                       (message source-id (intensity-update 10 :high))
                                       n1)
          n3 (core/run-tasks tasks1 n2)
          [tasks2 n4] (core/eval-cell source-id
                                       (message source-id (intensity-update 1 :low))
                                       n3)]
      (is (tq/queue-empty? tasks2))
      (is (= :high (selected-base (net/network-cell-strongest n4 out-id)))))))
