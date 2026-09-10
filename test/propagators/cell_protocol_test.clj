(ns propagators.cell-protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell]
            [propagators.cells.cell-protocol :as protocol]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.compound-information :as information]
            [propagators.datastructures.behavior :as behavior]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.event :as event]
            [propagators.datastructures.intensity :as intensity]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms :as tms]
            [propagators.generic-procedure :as generic]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.stdlib.prop :as stdlib-prop]))

(defn- install-protocol
  [n]
  (compile/install-and-run n (protocol/install-cell-protocol)))

(defn- install-intensity
  [n]
  (compile/install-and-run n (protocol/install-intensity-protocol)))

(defn- install-scope-source
  [n]
  (compile/install-and-run n (protocol/install-scope-source-protocol)))

(defn- install-dependency
  [n]
  (compile/install-and-run n (protocol/install-dependency-protocol)))

(defn- protocol-net
  []
  (install-protocol net/empty-net))

(defn- intensity-net
  []
  (install-intensity (protocol-net)))

(defn- scope-source-net
  []
  (install-scope-source (protocol-net)))

(defn- dependency-net
  []
  (install-dependency (protocol-net)))

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

(defn- selected-source-base
  [v]
  (scope-source/base-value v))

(deftest protocol-falls-back-to-built-in-merge-and-strongest
  (testing "without protocol generics, built-in behavior is unchanged"
    (is (= :x (merge/cell-merge value/nothing :x net/empty-net)))
    (is (= :x (merge/strongest-value :x net/empty-net))))

  (testing "installed but unextended protocol generics still fall back"
    (let [n (protocol-net)]
      (is (= :x (merge/cell-merge value/nothing :x n)))
      (is (= :x (merge/strongest-value :x n))))))

(deftest semantic-compound-values-use-native-built-ins-without-protocol-installers
  (let [event-update (event/active-event :slider :widget 1 4)
        event-content (merge/cell-merge value/nothing event-update net/empty-net)
        event-selected (merge/strongest-value event-content net/empty-net)
        behavior-update (behavior/latest-value 1 7 #{[:source 1]})
        behavior-content (merge/cell-merge value/nothing behavior-update net/empty-net)
        behavior-selected (merge/strongest-value behavior-content net/empty-net)
        tms-update (tms/distributed-input-update :claim 9 :premise 0 :source)
        tms-content (merge/cell-merge value/nothing tms-update net/empty-net)
        tms-selected (merge/strongest-value tms-content net/empty-net)]
    (is (= :event-content (information/semantic-kind event-content)))
    (is (= :behavior-content (information/semantic-kind behavior-content)))
    (is (= :distributed-tms (information/semantic-kind tms-content)))
    (is (= [4] (vec (vals (event/active-values event-selected)))))
    (is (= 7 (behavior/base-value behavior-selected)))
    (is (= 9 (tms/distributed-base-value tms-selected)))))

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

(deftest scope-source-protocol-merges-and-selects-nearest-source
  (testing "nearest source in the active scope chain wins"
    (let [n (scope-source-net)
          root :root
          child :child
          grandchild :grandchild
          chain [root child grandchild]
          root-value (scope-source/scope-value root grandchild chain :root-value)
          child-value (scope-source/scope-value child grandchild chain :child-value)
          content (-> value/nothing
                      (#(merge/cell-merge % root-value n))
                      (#(merge/cell-merge % child-value n)))]
      (is (= :child-value
             (selected-source-base (merge/strongest-value content n))))))

  (testing "same semantic candidate is idempotent even with compound-object slots"
    (let [n (scope-source-net)
          candidate (scope-source/scope-value :root :root [:root] :same)
          content (merge/cell-merge value/nothing candidate n)]
      (is (= content (merge/cell-merge content candidate n)))))

  (testing "equal nearest conflicting candidates contradict"
    (let [n (scope-source-net)
          left (scope-source/scope-value :child :child [:root :child] :left)
          right (scope-source/scope-value :child :child [:root :child] :right)
          content (-> value/nothing
                      (#(merge/cell-merge % left n))
                      (#(merge/cell-merge % right n)))]
      (is (= value/contradiction (merge/strongest-value content n)))))

  (testing "non-ancestor sources are retained but not strongest"
    (let [n (scope-source-net)
          unrelated (scope-source/scope-value :other :child [:root :child] :other)
          content (merge/cell-merge value/nothing unrelated n)]
      (is (= value/nothing (merge/strongest-value content n)))))

  (testing "old constructor arities derive closure and chain from structured source"
    (let [candidate (scope-source/scope-value :root :ignored [:root :child] :same)]
      (is (= :root (scope-source/source-scope candidate)))
      (is (= [:root :child] (scope-source/context-chain candidate)))
      (is (= :child (scope-source/closure-scope candidate)))))

  (testing "primitive propagator builds a scope-source candidate"
    (let [n (scope-source-net)
          source-id (new-node-id)
          chain-id (new-node-id)
          value-id (new-node-id)
          out-id (new-node-id)
          n0 (-> n
                 (#(reduce nb/install-cell
                           %
                           [source-id chain-id value-id out-id]))
                 (nb/seed-cell source-id :child)
                 (nb/seed-cell chain-id [:root :child])
                 (nb/seed-cell value-id :payload))
          [prop-id n1] ((scope-source/p:scope-value source-id
                                                      chain-id
                                                      value-id
                                                      out-id)
                        n0)
          n2 (nb/run-propagators n1 [prop-id])
          selected (net/network-cell-strongest n2 out-id)]
      (is (scope-source/scope-value? selected))
      (is (= :child (scope-source/source-scope selected)))
      (is (= [:root :child] (scope-source/context-chain selected)))
      (is (= :payload (scope-source/base-value selected)))))

  (testing "scope-source stores dependencies without legacy closure layers"
    (let [candidate (scope-source/scope-value :root nil [:root] :same #{:dep})]
      (is (= #{:dep} (obj/slot-value candidate :scope/dependencies)))
      (is (nil? (obj/slot-value candidate :scope/closure)))
      (is (nil? (obj/slot-value candidate :scope/chain)))
      (is (= #{:dep} (scope-source/dependencies candidate)))))

  (testing "equivalent candidates union provenance"
    (let [n (scope-source-net)
          left (scope-source/scope-value :child nil [:root :child] :same #{:left})
          right (scope-source/scope-value :child nil [:root :child] :same #{:right})
          content (-> value/nothing
                      (#(merge/cell-merge % left n))
                      (#(merge/cell-merge % right n)))
          selected (merge/strongest-value content n)]
      (is (= :same (scope-source/base-value selected)))
      (is (= #{:left :right} (scope-source/dependencies selected)))))

  (testing "candidate collection updates are accepted"
    (let [n (scope-source-net)
          root (scope-source/scope-value :root nil [:root :child] :root #{:root})
          child (scope-source/scope-value :child nil [:root :child] :child #{:child})
          content (merge/cell-merge value/nothing [root child] n)
          selected (merge/strongest-value content n)]
      (is (= :child (scope-source/base-value selected)))
      (is (= #{:child} (scope-source/dependencies selected)))))

  (testing "retarget and map-base preserve provenance"
    (let [candidate (scope-source/scope-value :root nil [:root] 2 #{:origin})
          retargeted (scope-source/retarget candidate nil [:root :child])
          mapped (scope-source/map-base retargeted inc)]
      (is (= [:root :child] (scope-source/context-chain mapped)))
      (is (= 3 (scope-source/base-value mapped)))
      (is (= #{:origin} (scope-source/dependencies mapped))))))

(deftest dependency-protocol-merges-by-base-and-unions-sources
  (testing "dependency values expose base and source layers"
    (let [v (dependency/dependency-value 3 #{:a})]
      (is (dependency/dependency-value? v))
      (is (= 3 (dependency/base-value v)))
      (is (= #{:a} (dependency/sources v)))))

  (testing "same base unions dependency sources"
    (let [n (dependency-net)
          left (dependency/dependency-value 3 #{:left})
          right (dependency/dependency-value 3 #{:right})
          content (-> value/nothing
                      (#(merge/cell-merge % left n))
                      (#(merge/cell-merge % right n)))
          strongest (merge/strongest-value content n)]
      (is (= 3 (dependency/base-value strongest)))
      (is (= #{:left :right} (dependency/sources strongest)))))

  (testing "duplicate update is idempotent"
    (let [n (dependency-net)
          update (dependency/dependency-value 3 #{:same})
          content (merge/cell-merge value/nothing update n)]
      (is (= content (merge/cell-merge content update n)))))

  (testing "conflicting bases contradict"
    (let [n (dependency-net)
          left (dependency/dependency-value 3 #{:left})
          right (dependency/dependency-value 4 #{:right})
          content (-> value/nothing
                      (#(merge/cell-merge % left n))
                      (#(merge/cell-merge % right n)))]
      (is (= value/contradiction content)))))

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

(deftest scheduler-wakes-only-when-protocol-strongest-changes
  (testing "contradiction provenance refinement wakes downstream"
    (let [source-id (new-node-id)
          out-id (new-node-id)
          first-conflict (value/contradiction-with-provenance #{:left})
          refined-conflict (value/contradiction-with-provenance #{:right})
          n0 (-> (nb/install-cells [source-id out-id]))
          [sync-prop n1] ((stdlib-prop/id source-id out-id) n0)
          [tasks1 n2] (core/eval-cell source-id
                                       (message source-id first-conflict)
                                       n1)
          n3 (core/run-tasks tasks1 n2)
          [tasks2 n4] (core/eval-cell source-id
                                       (message source-id refined-conflict)
                                       n3)]
      (is (queued? tasks2 sync-prop))
      (is (= #{:left :right}
             (value/contradiction-provenance
              (net/network-cell-strongest n4 source-id))))))

  (testing "dependency-only scope refinement wakes downstream"
    (let [source-id (new-node-id)
          out-id (new-node-id)
          left (scope-source/scope-value :root nil [:root] :same #{:left})
          right (scope-source/scope-value :root nil [:root] :same #{:right})
          n0 (-> (scope-source-net)
                 (nb/install-cell source-id)
                 (nb/install-cell out-id))
          [sync-prop n1] ((stdlib-prop/id source-id out-id) n0)
          [tasks1 n2] (core/eval-cell source-id (message source-id left) n1)
          n3 (core/run-tasks tasks1 n2)
          [tasks2 n4] (core/eval-cell source-id (message source-id right) n3)]
      (is (queued? tasks2 sync-prop))
      (is (= #{:left :right}
             (scope-source/dependencies
              (net/network-cell-strongest n4 source-id))))))

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
