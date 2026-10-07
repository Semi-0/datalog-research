(ns propagators.network-patch-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-patch :as patch]
            [propagators.relationship :as relationship]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(deftest output-extension-preserves-activation-and-existing-topology
  (let [[input output member id] (repeatedly 4 new-node-id)
        base (reduce nb/ensure-cell net/empty-net [input output member])
        [id initial] ((prop/construct-propagator
                       id :test/interface (fn [in out _] [in out])
                       [input] [output]) (nb/seed-cell base member 42))
        effect (patch/extend-propagator-outputs id [member member])
        [tasks extended] (runner/apply-patch nil effect initial)
        [_ repeated] (runner/apply-patch nil effect extended)
        graph (net/net-graph extended)
        activate (prop/prop-f (get (net/net-env extended) id))]
    (is (= #{output} (:outputs (get (net/net-graph initial) id))))
    (is (= #{output member} (:outputs (get graph id))))
    (is (contains? (:inputs (get graph member)) id))
    (is (= 42 (net/network-cell-value extended member)))
    (is (= [[input] [output]]
           (activate (:inputs (get graph id)) (:outputs (get graph id)) extended)))
    (is (identical? activate (prop/prop-f (get (net/net-env initial) id))))
    (is (= (net/net-relationship initial) (net/net-relationship extended)))
    (is (tq/queue-empty? tasks))
    (is (= extended repeated))
    (is (= extended (nb/extend-propagator-outputs extended id [])))))

(deftest output-extension-relates-only-new-cells-to-emitter
  (let [[id old member emitter-id] (repeatedly 4 new-node-id)
        emitter (relationship/node-key [:outer] emitter-id)
        [_ initial] ((prop/construct-propagator id :test/noop (fn [_ _ _] []) [] [old])
                     (nb/ensure-cell net/empty-net old))
        effect (patch/extend-propagator-outputs id [old member])
        [tasks extended] (runner/apply-patch emitter effect initial)
        [_ repeated] (runner/apply-patch emitter effect extended)]
    (is (= #{(relationship/node-key [:outer] member)}
           (relationship/children (net/net-relationship extended) emitter)))
    (is (empty? (relationship/parents (net/net-relationship extended)
                                    (relationship/node-key [:outer] old))))
    (is (empty? (relationship/parents (net/net-relationship extended)
                                    (relationship/node-key [:outer] id))))
    (is (tq/queue-empty? tasks))
    (is (= extended repeated))))

(deftest output-extension-rejects-invalid-boundaries-atomically
  (let [[id output missing other] (repeatedly 4 new-node-id)
        [_ initial] ((prop/construct-propagator id :test/noop (fn [_ _ _] []) [] [output])
                     (nb/ensure-cell net/empty-net output))
        [_ initial] ((prop/construct-propagator other :test/other (fn [_ _ _] []) [] [])
                     initial)]
    (doseq [[target outputs] [[missing [output]] [output [missing]]
                              [id [missing other]] [id [:invalid]]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid propagator output extension"
                            (runner/apply-patch nil
                              (patch/extend-propagator-outputs target outputs) initial))))
    (is (not (contains? (net/net-env initial) missing)))
    (is (= #{output} (:outputs (get (net/net-graph initial) id))))))

(deftest declaration-patch-records-only-new-topology
  (let [emitter (relationship/node-key [:outer] (new-node-id))
        input-id (new-node-id)
        output-id (new-node-id)
        propagator-id (new-node-id)
        declaration (patch/declare-propagator
                     propagator-id
                     :test/copy
                     [input-id]
                     [output-id]
                     (fn [_inputs _outputs _network] []))
        [tasks installed]
        (runner/apply-patch emitter declaration net/empty-net)
        [scheduled remaining] (tq/pop-task tasks)
        expected-children
        (set (map #(relationship/node-key [:outer] %)
                  [input-id output-id propagator-id]))
        [repeat-tasks repeated]
        (runner/apply-patch emitter declaration installed)]
    (is (= propagator-id scheduled))
    (is (tq/queue-empty? remaining))
    (is (= expected-children
           (relationship/children
            (net/net-relationship installed)
            emitter)))
    (is (tq/queue-empty? repeat-tasks))
    (is (= (net/net-relationship installed)
           (net/net-relationship repeated)))))

(deftest cell-and-unknown-patch-branches-are-explicit
  (let [cell-id (new-node-id)
        network (nb/install-cell net/empty-net cell-id)
        [tasks updated]
        (runner/apply-patch nil (message/message cell-id 42) network)]
    (testing "ordinary cell patches reuse the existing evaluator"
      (is (tq/queue-empty? tasks))
      (is (= 42 (net/network-cell-value updated cell-id))))
    (testing "the fallback rejects unknown patches"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"unknown network patch"
           (runner/apply-patch nil {:op :test/unknown} network))))))
