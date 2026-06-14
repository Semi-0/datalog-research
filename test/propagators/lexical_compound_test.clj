(ns propagators.lexical-compound-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.io :as io]
            [propagators.lexical-compound :as lexical]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.reality :as reality]
            [propagators.recursive-compound-test :as recursive-test]
            [propagators.stdlib.prop :as prop]))

(defn- strongest [n id]
  (net/network-cell-strongest n id))

(defn- child-add-net []
  (let [in (ids/new-node-id)
        out (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell in)
               (nb/install-cell out))
        [in-prop n1] ((reality/p:reality-in :in in) n0)
        [id-prop n2] ((prop/id in out) n1)
        [out-prop n3] ((reality/p:reality-out :out out) n2)]
    {:net n3
     :in in
     :out out
     :props [in-prop id-prop out-prop]}))

(defn- child-bisync-net []
  (let [x (ids/new-node-id)
        y (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell x)
               (nb/install-cell y))
        [x-in n1] ((reality/p:reality-in :x-in x) n0)
        [y-in n2] ((reality/p:reality-in :y-in y) n1)
        [x->y n3] ((prop/id x y) n2)
        [y->x n4] ((prop/id y x) n3)
        [x-out n5] ((reality/p:reality-out :x-out x) n4)
        [y-out n6] ((reality/p:reality-out :y-out y) n5)]
    {:net n6
     :x x
     :y y
     :props [x-in y-in x->y y->x x-out y-out]}))

(defn- child-nested-slot-net []
  (let [source (ids/new-node-id)
        right (ids/new-node-id)
        a (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell source)
               (nb/install-cell right)
               (nb/install-cell a))
        [source-in n1] ((reality/p:reality-in :source source) n0)
        [right-slot n2] ((obj/p:slot :right right source) n1)
        [a-slot n3] ((obj/p:slot :a a right) n2)
        [a-out n4] ((reality/p:reality-out :a a) n3)]
    {:net n4
     :source source
     :a a
     :props [source-in right-slot a-slot a-out]}))

(defn- child-nested-bidirectional-slot-net []
  (let [source (ids/new-node-id)
        right (ids/new-node-id)
        a (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell source)
               (nb/install-cell right)
               (nb/install-cell a))
        [source-in n1] ((reality/p:reality-in :source source) n0)
        [a-in n2] ((reality/p:reality-in :a a) n1)
        [right-slot n3] ((obj/p:legacy-slot :right right source) n2)
        [a-slot n4] ((obj/p:legacy-slot :a a right) n3)
        [right-out n5] ((reality/p:reality-out :right-out right) n4)]
    {:net n5
     :source source
     :right right
     :a a
     :props [source-in a-in right-slot a-slot right-out]}))

(defn- child-recursive-nested-map-net [source-shape]
  (let [closure-value ((var-get #'recursive-test/fib-frame-closure) :accumulating {})
        closure-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        source-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id closure-value closure-value)
               (nb/install-cell acc-id)
               (nb/install-cell source-id)
               (nb/install-cell out-id))
        [source-in n1] ((reality/p:reality-in :source source-id) n0)
        {n2 :net}
        (obj/install-accessor-nested-recursive-map-with-accumulator
         n1
         closure-id
         acc-id
         source-id
         source-shape
         out-id)
        [out-prop n3] ((reality/p:reality-out :out out-id) n2)]
    {:net n3
     :source source-id
     :out out-id
     :props [source-in out-prop]}))

(deftest lexical-compound-runs-child-network-without-diffing
  (let [{child :net in :in} (child-add-net)
        child-id (ids/new-node-id)
        parent-in (ids/new-node-id)
        parent-out (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell child-id child child)
               (nb/install-cell parent-in 7 7)
               (nb/install-cell parent-out))
        [compound n1] ((lexical/p:lexical-compound
                        child-id
                        [[:in parent-in in]]
                        [[:out parent-out]])
                       n0)
        n2 (core/run-tasks [compound] n1)
        child2 (strongest n2 child-id)]
    (is (= 7 (strongest n2 parent-out)))
    (is (= 7 (net/network-cell-strongest child2 in)))
    (is (empty? (:outbox (net/net-io child2))))))

(deftest lexical-compound-supports-bidirectional-child-topology
  (let [{child :net x :x y :y} (child-bisync-net)
        child-id (ids/new-node-id)
        parent-x (ids/new-node-id)
        parent-y (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell child-id child child)
               (nb/install-cell parent-x 10 10)
               (nb/install-cell parent-y))
        [compound n1] ((lexical/p:lexical-compound
                        child-id
                        [[:x-in parent-x x]
                         [:y-in parent-y y]]
                        [[:x-out parent-x]
                         [:y-out parent-y]])
                       n0)
        n2 (core/run-tasks [compound] n1)]
    (is (= 10 (strongest n2 parent-y)))
    (is (= 10 (strongest n2 parent-x)))))

(deftest lexical-compound-reads-nested-compound-object-through-accessors
  (let [{child :net source :source} (child-nested-slot-net)
        child-id (ids/new-node-id)
        parent-source (ids/new-node-id)
        parent-a (ids/new-node-id)
        source-value {:left [0 1]
                      :right {:a 3}}
        n0 (-> net/empty-net
               (nb/install-cell child-id child child)
               (nb/install-cell parent-source source-value source-value)
               (nb/install-cell parent-a))
        [compound n1] ((lexical/p:lexical-compound
                        child-id
                        [[:source parent-source source]]
                        [[:a parent-a]])
                       n0)
        n2 (core/run-tasks [compound] n1)]
    (is (= 3 (strongest n2 parent-a)))))

(deftest lexical-compound-writes-nested-slot-through-bidirectional-accessor
  (let [{child :net right :right a :a} (child-nested-bidirectional-slot-net)
        child-id (ids/new-node-id)
        parent-a (ids/new-node-id)
        parent-right (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell child-id child child)
               (nb/install-cell parent-a 9 9)
               (nb/install-cell parent-right))
        [compound n1] ((lexical/p:lexical-compound
                        child-id
                        [[:a parent-a a]]
                        [[:right-out parent-right]])
                       n0)
        n2 (core/run-tasks [compound] n1)]
    (is (= {:a 9}
           ((var-get #'recursive-test/compound->data)
            (strongest n2 parent-right))))))

(deftest lexical-compound-runs-recursive-map-over-nested-compound-object
  (let [source-value {:left [0 1 2]
                      :right {:a 3
                              :b [4 5]}}
        expected {:left [0 1 1]
                  :right {:a 2
                          :b [3 5]}}
        {child :net source :source} (child-recursive-nested-map-net source-value)
        child-id (ids/new-node-id)
        parent-source (ids/new-node-id)
        parent-out (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell child-id child child)
               (nb/install-cell parent-source source-value source-value)
               (nb/install-cell parent-out))
        [compound n1] ((lexical/p:lexical-compound
                        child-id
                        [[:source parent-source source]]
                        [[:out parent-out]])
                       n0)
        n2 (core/run-tasks [compound] n1)
        value (strongest n2 parent-out)]
    (is (= expected ((var-get #'recursive-test/compound->data) value)))))
