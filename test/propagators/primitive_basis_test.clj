(ns propagators.primitive-basis-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.compile :as compile]
            [propagators.cursor :as cursor]
            [propagators.declaration :as decl]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.stdlib.prop :as stdlib-prop]))

(defn- strongest
  [n id]
  (net/network-cell-strongest n id))

(defn- run-installed
  [n prop-ids]
  (nb/run-propagators n (vec prop-ids)))

(defn- run-one
  [installer seeds out-id]
  (let [ids (vec (distinct (conj (mapv first seeds) out-id)))
        n0 (nb/install-cells ids)
        [prop-id n1] (installer n0)
        n2 (reduce (fn [n [id v]] (nb/seed-cell n id v)) n1 seeds)
        n3 (run-installed n2 [prop-id])]
    (strongest n3 out-id)))

(defn- cursor-items
  [cursor-value]
  (loop [current cursor-value
         items []]
    (cond
      (value/nothing? current) items
      (value/contradiction? current) value/contradiction
      :else (recur (cursor/cdr-value current)
                   (conj items (cursor/car-value current))))))

(defn- public-entry
  [entry]
  (select-keys entry [:slot-key :path :slot-value]))

(deftest value-gates-support-one-armed-reducer-control
  (testing "prop/nothing? classifies cursor termination"
    (let [in (new-node-id)
          out (new-node-id)]
      (is (= true (run-one (stdlib-prop/nothing? in out)
                           [[in value/nothing]]
                           out)))
      (is (= false (run-one (stdlib-prop/nothing? in out)
                            [[in :value]]
                            out)))
      (is (= value/contradiction
             (run-one (stdlib-prop/nothing? in out)
                      [[in value/contradiction]]
                      out)))))

  (testing "prop/when emits only on true"
    (let [v (new-node-id)
          condition (new-node-id)
          out (new-node-id)]
      (is (= :sent (run-one (stdlib-prop/when v condition out)
                            [[v :sent] [condition true]]
                            out)))
      (is (= value/nothing
             (run-one (stdlib-prop/when v condition out)
                      [[v :sent] [condition false]]
                      out)))
      (is (= value/nothing
             (run-one (stdlib-prop/when v condition out)
                      [[v :sent] [condition value/nothing]]
                      out)))
      (is (= value/contradiction
             (run-one (stdlib-prop/when v condition out)
                      [[v :sent] [condition value/contradiction]]
                      out))))))

(deftest when-apply-network-is-a-one-armed-network-gate
  (let [condition (new-node-id)
        expander-id (new-node-id)
        acc-id (new-node-id)
        out-id (new-node-id)
        expander (closure/closure
                  (fn [_closure-net _inputs _outputs declaration-net]
                    (net/assoc-net-dict-entry declaration-net :expanded true))
                  net/empty-net)
        run (fn [condition-value]
              (let [n0 (-> net/empty-net
                           (nb/install-cell condition condition-value condition-value)
                           (nb/install-cell expander-id expander expander)
                           (nb/install-cell acc-id net/empty-net net/empty-net)
                           (nb/install-cell out-id))
                    [prop-id n1] ((closure/p:when-apply-network
                                   condition expander-id acc-id out-id)
                                  n0)
                    n2 (run-installed n1 [prop-id])]
                (strongest n2 out-id)))]
    (testing "false and nothing emit no network value"
      (is (= value/nothing (run false)))
      (is (= value/nothing (run value/nothing))))
    (testing "true applies the expander"
      (is (= true (net/network-dict-entry (run true) :expanded))))))

(deftest pure-cursor-access-does-not-create-compound-topology
  (testing "empty cursor reads as nothing"
    (let [cur (new-node-id)
          head (new-node-id)
          tail (new-node-id)
          n0 (-> net/empty-net
                 (nb/install-cell cur value/nothing value/nothing)
                 (nb/install-cell head)
                 (nb/install-cell tail))
          [car-prop n1] ((cursor/p:car head cur) n0)
          [cdr-prop n2] ((cursor/p:cdr tail cur) n1)
          n3 (run-installed n2 [car-prop cdr-prop])]
      (is (= value/nothing (strongest n3 head)))
      (is (= value/nothing (strongest n3 tail)))
      (is (nil? (net/network-dict-entry (strongest n3 cur) :slot-index)))))

  (testing "non-empty cursor exposes head and tail"
    (let [cur (new-node-id)
          head (new-node-id)
          tail (new-node-id)
          cursor-value (cursor/cursor [:a :b])
          n0 (-> net/empty-net
                 (nb/install-cell cur cursor-value cursor-value)
                 (nb/install-cell head)
                 (nb/install-cell tail))
          [car-prop n1] ((cursor/p:car head cur) n0)
          [cdr-prop n2] ((cursor/p:cdr tail cur) n1)
          n3 (run-installed n2 [car-prop cdr-prop])]
      (is (= :a (strongest n3 head)))
      (is (= [:b] (cursor-items (strongest n3 tail)))))))

(deftest slot-cursor-projects-compound-slots-stably
  (testing "maps are ordered by printable slot key"
    (let [source (new-node-id)
          out (new-node-id)
          result (run-one (obj/p:slot-cursor source out)
                          [[source {:b 2 :a 1}]]
                          out)]
      (is (= [{:slot-key :a :path [:a] :slot-value 1}
              {:slot-key :b :path [:b] :slot-value 2}]
             (mapv public-entry (cursor-items result))))))

  (testing "vectors expose element slots and omit read-only count"
    (let [source (new-node-id)
          out (new-node-id)
          result (run-one (obj/p:slot-cursor source out)
                          [[source [:a :b]]]
                          out)]
      (is (= [{:slot-key 0 :path [0] :slot-value :a}
              {:slot-key 1 :path [1] :slot-value :b}]
             (mapv public-entry (cursor-items result))))))

  (testing "accessor networks expose source slots"
    (let [source (new-node-id)
          out (new-node-id)
          result (run-one (obj/p:slot-cursor source out)
                          [[source (obj/as-accessor-network {:b 2 :a 1})]]
                          out)]
      (is (= [{:slot-key :a :path [:a] :slot-value 1}
              {:slot-key :b :path [:b] :slot-value 2}]
             (mapv public-entry (cursor-items result))))))

  (testing "empty compound emits empty cursor"
    (let [source (new-node-id)
          out (new-node-id)]
      (is (= value/nothing
             (run-one (obj/p:slot-cursor source out)
                      [[source {}]]
                      out))))))

(deftest bind-network-carries-current-item-without-persisting-it
  (let [expander-id (new-node-id)
        item-id (new-node-id)
        bound-id (new-node-id)
        acc-id (new-node-id)
        out-id (new-node-id)
        item {:slot-key :x :path [:x]}
        expander (closure/closure
                  (fn [closure-net _inputs _outputs declaration-net]
                    (-> declaration-net
                        (net/assoc-net-dict-entry :seen
                                                  (closure/current-item closure-net))
                        (net/assoc-net-dict-entry closure/current-item-key
                                                  :would-leak)))
                  net/empty-net)
        n0 (-> net/empty-net
               (nb/install-cell expander-id expander expander)
               (nb/install-cell item-id item item)
               (nb/install-cell bound-id)
               (nb/install-cell acc-id net/empty-net net/empty-net)
               (nb/install-cell out-id))
        [bind-prop n1] ((closure/p:bind-network expander-id item-id bound-id) n0)
        [apply-prop n2] ((closure/p:apply-network bound-id acc-id out-id) n1)
        n3 (run-installed n2 [bind-prop apply-prop])
        expanded (strongest n3 out-id)]
    (is (= item (net/network-dict-entry expanded :seen)))
    (is (nil? (net/network-dict-entry expanded closure/current-item-key)))))

(deftest derived-reduce-cursor-installs-two-exit-control
  (testing "done branch publishes the accumulator"
    (let [cursor-id (new-node-id)
          step-id (new-node-id)
          acc-id (new-node-id)
          out-id (new-node-id)
          acc-net (net/assoc-net-dict-entry net/empty-net :result :done)
          step (decl/closure (fn [_ n] n))
          n0 (-> net/empty-net
                 (nb/install-cell cursor-id value/nothing value/nothing)
                 (nb/install-cell step-id step step)
                 (nb/install-cell acc-id acc-net acc-net)
                 (nb/install-cell out-id))
          [prop-ids n1] ((decl/reduce-cursor cursor-id step-id acc-id out-id) n0)
          n2 (run-installed n1 prop-ids)]
      (is (= :done (net/network-dict-entry (strongest n2 out-id) :result)))
      (is (= value/nothing
             (strongest n2 (net/network-dict-entry n2 decl/reducer-branch-key))))))

  (testing "more branch emits an expanded branch network"
    (let [cursor-id (new-node-id)
          step-id (new-node-id)
          acc-id (new-node-id)
          out-id (new-node-id)
          step (decl/closure (fn [_ n] (net/assoc-net-dict-entry n :stepped true)))
          n0 (-> net/empty-net
                 (nb/install-cell cursor-id (cursor/cursor [:x]) (cursor/cursor [:x]))
                 (nb/install-cell step-id step step)
                 (nb/install-cell acc-id net/empty-net net/empty-net)
                 (nb/install-cell out-id))
          [prop-ids n1] ((decl/reduce-cursor cursor-id step-id acc-id out-id) n0)
          n2 (run-installed n1 prop-ids)
          branch-id (net/network-dict-entry n2 decl/reducer-branch-key)
          branch (strongest n2 branch-id)]
      (is (= value/nothing (strongest n2 out-id)))
      (is (net/network? branch))
      (is (seq (net/network-dict-entry branch decl/reducer-props-key))))))

(deftest old-compile-dsl-exposes-primitive-basis
  (let [ctx (compile/eval-layered
             net/empty-net
             (compile/default-installers)
             {'v :value}
             '(do
                (let-cell [x done? out]
                  (seed x v)
                  (prop/nothing? x done?)
                  (prop/when x done? out))))
        done-id (compile/cell-ref ctx 'done?)
        out-id (compile/cell-ref ctx 'out)
        n (run-installed (:net ctx) (:props ctx))]
    (is (= false (strongest n done-id)))
    (is (= value/nothing (strongest n out-id)))))
