(ns propagators.compiler-2-empty-list-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.compound-object.merge :as owner]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as support-collection]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.stdlib.premise-state :as premise-state]))

(defn payload [network id]
  (let [current (net/network-cell-strongest network id)]
    (if (datum/layer-present? current :base)
      (datum/layer-value current :base)
      current)))

(defn evaluate [source]
  (let [compiled (compiler/compile-source source)
        network (nb/run-propagators (:net compiled) (:props compiled))]
    (payload network (:cell compiled))))

(defn list-expression [expression]
  (str "(let-cell [] "
       (slurp "propagators/compiler_2/runtime/lain/list.lain")
       " (define increment (network [x] (+ x 1))) " expression ")"))

(deftest empty-is-known-and-open-cons-tails-remain-pending
  (doseq [[source expected]
          [["(list)" basis/list-empty-marker]
           ["(empty-list? (list))" true]
           ["(empty-list? (list 1))" false]
           ["(empty-list? (cdr (list 1)))" true]
           ["(empty-list? (cdr (cdr (list 1 2))))" true]
           ["(empty-list? 42)" false]
           ["(let-cell [tail] (empty-list? tail))" value/nothing]
           ["(let-cell [tail] (empty-list? (cdr (cons 1 tail))))" value/nothing]
           ["(let-cell [tail] (empty-list? (cons 1 tail)))" false]]]
    (testing source
      (is (= expected (evaluate source))))))

(deftest list-networks-preserve-empty-completion-and-open-prefixes
  (doseq [[expression expected]
          [["(empty-list? (map-list increment (list)))" true]
           ["(car (map-list increment (list 1 2)))" 2]
           ["(empty-list? (cdr (cdr (map-list increment (list 1 2)))))" true]
           ["(empty-list? (filter-list (network [x] (> x 9)) (list 1 2)))" true]
           ["(empty-list? (cdr (filter-list (network [x] (>= x 2)) (list 1 2))))" true]
           ["(empty-list? (cdr (zip-with (network [x y] (+ x y)) (list 1 2) (list 10))))" true]
           ["(empty-list? (cdr (cdr (car (zip (list 1) (list 10))))))" true]
           ["(let-cell [tail] (empty-list? (cdr (map-list increment (cons 1 tail)))))" value/nothing]]]
    (testing expression
      (is (= expected (evaluate (list-expression expression)))))))

(defn publication [base source revision status]
  (let [support #{{:source source :timestamp revision :premises-status status}}]
    (support-collection/content
     {:base base :support support :premise-state support})))

(defn publish [network id content]
  (let [[_ updated] (core/eval-cell id (message/message id content) network)]
    (nb/run-propagators updated (nb/neighbor-propagator-ids updated id))))

(deftest empty-list-predicate-follows-supported-revisions-and-withdrawal
  (let [compiled (compiler/compile-source
                  "(let-cell [input] (list input (empty-list? input)))")
        initial (nb/run-propagators (:net compiled) (:props compiled))
        input (obj/existing-slot-cell-id initial :car (:cell compiled))
        second-node (obj/existing-slot-cell-id initial :cdr (:cell compiled))
        output (obj/existing-slot-cell-id initial :car second-node)
        [source head tail] (repeatedly 3 ids/new-node-id)
        prepared (reduce nb/ensure-cell initial [head tail])
        cons-owner (-> (owner/accessor-declaration :car head)
                       (owner/register-accessor-parent :cdr tail))
        states [[basis/list-empty-marker 1 :active true]
                [cons-owner 2 :active false]
                [value/nothing 3 :active value/nothing]
                [basis/list-empty-marker 4 :active true]
                [basis/list-empty-marker 4 :retracted value/nothing]
                [basis/list-empty-marker 5 :active true]
                [value/contradiction 6 :active false]]]
    (is (value/nothing? (payload initial output)))
    (reduce
     (fn [network [base revision status expected]]
       (let [updated (publish network input (publication base source revision status))
             current (net/network-cell-strongest updated output)
             state (premise-state/states-of (net/network-cell-strongest updated input))]
         (is (= expected (payload updated output)))
         (is (= state (premise-state/states-of current)))
         (when (= status :active)
           (is (= state (datum/support-of current))))
         updated))
     prepared states)))
