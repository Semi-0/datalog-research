(ns propagators.ttms-branching-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-branching :as branching]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- premise [source timestamp status]
  {:source source :timestamp timestamp :premises-status status})

(defn- supported [base & support]
  (obj/compound-object {:base base :support (set support)}))

(defn- bindings []
  (extension/extension-bindings branching/session-extension))

(defn- install [kind inputs]
  (let [[c a b out other] (repeatedly 5 ids/new-node-id)
        prepared (reduce (fn [n [id v]] (nb/install-cell n id v v))
                         (nb/install-cells [c a b out other])
                         (map vector [c a b] inputs))
        args (case kind
               if [c a b]
               switch [a c]
               branch [c a out b other])
        [network tasks output] ((operator/operator-install (get (into {} (bindings)) kind))
                                prepared args out)]
    {:network network :tasks tasks :output output :other other :condition c :a a :b b}))

(defn- emitted [{:keys [network tasks]}]
  (into {} (map (fn [patch]
                 [(message/message-id patch) (first (:support/observations (:value patch)))]))
        ((prop/prop-f (net/network-env-lookup network (first tasks))) [] [] network)))

(defn- publish [network id base support]
  (let [[tasks n] (core/eval-cell id
                   (message/message id (collection/content {:base base :support support}))
                   network)]
    (nb/run-propagators n tasks)))

(deftest primitives-select-only-condition-and-selected-support
  (let [[c a b] (repeatedly 3 ids/new-node-id)
        cp (premise c 1 :active) ap (premise a 1 :active) bp (premise b 1 :active)]
    (doseq [condition [true false nil 0]]
      (let [fixture (install 'if [(supported condition cp) (supported 10 ap) (supported 20 bp)])
            result (get (emitted fixture) (:output fixture))]
        (is (= {:base (if condition 10 20)
                :support (if condition #{cp ap} #{cp bp})} result))))
    (doseq [kind ['switch 'branch] condition [true false]]
      (let [{:keys [output other] :as fixture}
            (install kind [(supported condition cp) (supported 10 ap) (supported 20 bp)])
            results (emitted fixture)]
        (is (= (if condition {:base 10 :support #{cp ap}}
                           {:base value/nothing :support #{cp}})
               (get results output)))
        (when (= kind 'branch)
          (is (= (if condition {:base value/nothing :support #{cp}}
                             {:base 20 :support #{cp bp}})
                 (get results other))))))))

(deftest unusable-conditions-and-values-keep-their-support
  (let [[c a b] (repeatedly 3 ids/new-node-id)
        cp (premise c 1 :active) cr (premise c 2 :retracted)
        ap (premise a 1 :active) br (premise b 2 :retracted)]
    (doseq [condition [(supported value/nothing cp)
                       (supported value/contradiction cp)
                       (supported true cr)
                       (supported true cp (premise c 2 :active))]]
      (let [fixture (install 'if [condition (supported 10 ap) (supported 20 br)])]
        (is (= {:base (datum/layer-value condition :base)
                :support (datum/support-of condition)}
               (get (emitted fixture) (:output fixture))))))
    (doseq [base [value/nothing value/contradiction]]
      (let [fixture (install 'if [(supported true cp) (supported base ap) (supported 20 br)])]
        (is (= {:base base :support #{cp ap}}
               (get (emitted fixture) (:output fixture))))))
    (let [fixture (install 'if [true 10 20])]
      (is (= {:base 10 :support #{}} (get (emitted fixture) (:output fixture)))))))

(deftest compiled-if-and-cond-use-the-extension
  (doseq [source ["(if c a b)" "(cond [c a else b])"]]
    (let [[c a b] (repeatedly 3 ids/new-node-id)
          cp (premise c 1 :active) ap (premise a 1 :active) bp (premise b 1 :active)
          initial (-> (nb/install-cells [c a b])
                      (publish c true #{cp}) (publish a 10 #{ap}) (publish b 20 #{bp}))
          compiled (compiler/compile-expr-with-bindings
                    (parser/parse-string source)
                    (into (vec (basis/default-bindings))
                          (concat (bindings) [['c (env/cell-binding c)]
                                              ['a (env/cell-binding a)]
                                              ['b (env/cell-binding b)]]))
                    {:net initial})
          after (nb/run-propagators (:net compiled) (:props compiled))
          output (:cell compiled)
          result (net/network-cell-strongest after output)
          changed (publish after b value/nothing #{(premise b 2 :retracted)})
          rerun (nb/run-propagators changed (:props compiled))]
      (is (= 10 (datum/layer-value result :base)))
      (is (= #{cp ap} (datum/support-of result)))
      (is (= result (net/network-cell-strongest changed output)))
      (is (= (net/net-env changed) (net/net-env rerun))))))

(deftest selected-retraction-then-switching-to-healthy-branch
  (let [{:keys [network tasks condition a b output] :as fixture}
        (install 'if [value/nothing value/nothing value/nothing])
        c1 (premise condition 1 :active) c2 (premise condition 2 :active)
        a1 (premise a 1 :active) a2 (premise a 2 :retracted)
        b1 (premise b 1 :active)
        active (-> network (publish condition true #{c1})
                   (publish a 10 #{a1}) (publish b 20 #{b1}))
        withdrawn (publish active a value/nothing #{a2})
        changed (publish withdrawn condition false #{c2})
        result (net/network-cell-strongest changed output)
        patches (emitted (assoc fixture :network changed))]
    (is (value/unusable? (net/network-cell-strongest withdrawn output)))
    ;; Primitive emission is correct independently of collection projection.
    (is (= {:base 20 :support #{c2 b1}} (get patches output)))
    (is (= 20 (datum/layer-value result :base)))
    (is (= #{c2 b1} (datum/support-of result)))
    (is (not (value/unusable? result)))
    (is (= (set (keys (net/net-env network))) (set (keys (net/net-env changed)))))
    (is (= (net/net-env changed) (net/net-env (nb/run-propagators changed tasks))))))

(defn- compile-chain [source values]
  (let [cells (zipmap (keys values) (repeatedly (count values) ids/new-node-id))
        initial (reduce-kv (fn [n name base]
                             (let [id (cells name)]
                               (publish n id base #{(premise id 1 :active)})))
                           (nb/install-cells (vals cells)) values)
        compiled (compiler/compile-expr-with-bindings
                  (parser/parse-string source)
                  (into (vec (basis/default-bindings))
                        (concat (bindings)
                                (map (fn [[name id]] [name (env/cell-binding id)]) cells)))
                  {:net initial})]
    {:network (nb/run-propagators (:net compiled) (:props compiled))
     :tasks (:props compiled) :output (:cell compiled) :cells cells}))

(defn- assert-projection [network output base support]
  (let [result (net/network-cell-strongest network output)]
    (is (= base (datum/layer-value result :base)))
    (is (= support (datum/support-of result)))))

(deftest compiled-switch-and-branch-withdraw-and-recover
  (doseq [source ["(switch a c)"
                  "(let-cell [yes no] (branch c a yes b no) yes)"]]
    (let [{:keys [network cells output]} (compile-chain source {'c true 'a 10 'b 20})
          c (cells 'c) a (cells 'a)
          a1 (premise a 1 :active)
          disabled (publish network c false #{(premise c 2 :active)})
          enabled (publish disabled c true #{(premise c 3 :active)})
          withdrawn (publish enabled c value/nothing #{(premise c 4 :retracted)})
          restored (publish withdrawn c true #{(premise c 5 :active)})]
      (assert-projection network output 10 #{(premise c 1 :active) a1})
      (assert-projection disabled output value/nothing #{(premise c 2 :active)})
      (assert-projection enabled output 10 #{(premise c 3 :active) a1})
      (assert-projection withdrawn output value/nothing #{(premise c 4 :retracted)})
      (assert-projection restored output 10 #{(premise c 5 :active) a1})
      (is (= (set (keys (net/net-env network)))
             (set (keys (net/net-env restored))))))))

(deftest nested-cond-selects-tested-conditions-and-selected-value-only
  (let [{:keys [network cells output tasks]}
        (compile-chain "(cond [c a d b else z])"
                       {'c false 'd true 'a 10 'b 10 'z 10})
        c (cells 'c) d (cells 'd) a (cells 'a) b (cells 'b) z (cells 'z)
        choose-a (publish network c true #{(premise c 2 :active)})
        retract-b (publish choose-a b value/nothing #{(premise b 2 :retracted)})
        choose-z (-> retract-b
                     (publish d false #{(premise d 2 :active)})
                     (publish c false #{(premise c 3 :active)}))]
    (assert-projection network output 10
                       #{(premise c 1 :active) (premise d 1 :active) (premise b 1 :active)})
    (assert-projection choose-a output 10 #{(premise c 2 :active) (premise a 1 :active)})
    (assert-projection retract-b output 10 #{(premise c 2 :active) (premise a 1 :active)})
    (assert-projection choose-z output 10
                       #{(premise c 3 :active) (premise d 2 :active) (premise z 1 :active)})
    (is (= (net/net-env choose-z) (net/net-env (nb/run-propagators choose-z tasks))))))

(deftest source-arrival-orders-converge-after-branch-switch
  (let [{:keys [network cells output]} (compile-chain "(if c a b)" {'c true 'a 10 'b 20})
        c (cells 'c) a (cells 'a) b (cells 'b)
        updates [[c false #{(premise c 2 :active)}]
                 [a value/nothing #{(premise a 2 :retracted)}]
                 [b 30 #{(premise b 2 :active)}]]]
    (doseq [order [[0 1 2] [0 2 1] [1 0 2] [1 2 0] [2 0 1] [2 1 0]]]
      (let [after (reduce (fn [n i] (apply publish n (updates i))) network order)]
        (assert-projection after output 30 #{(premise c 2 :active) (premise b 2 :active)})
        (is (not (value/unusable? (net/network-cell-strongest after output))))))))
