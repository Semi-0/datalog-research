(ns propagators.observation-transport-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.core :as core]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-primitives :as primitives]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.observation :as observation]
            [propagators.stdlib.supported-procedure :as supported]))

(defn- premise [source timestamp status]
  {:source source :timestamp timestamp :premises-status status})

(defn- observed [base p]
  (collection/content {:base base :support #{p} :premise-state #{p}}))

(defn- publish [network target update]
  (let [[tasks patched] (core/eval-cell target (message/message target update) network)]
    (nb/run-propagators patched tasks)))

(defn- verify-lifecycle [{:keys [network source outputs tasks offset]}]
  (let [topology (set (keys (net/net-env network)))
        lifecycle [[(observed 10 (premise source 1 :active)) 10 1 :active]
                   [(observed 20 (premise source 2 :active)) 20 2 :active]
                   [(collection/content {:premise-state #{(premise source 3 :retracted)}})
                    value/nothing 3 :retracted]
                   [(collection/content {:premise-state #{(premise source 4 :active)}})
                    value/nothing 4 :active]
                   [(observed 7 (premise source 4 :active)) 7 4 :active]]]
    (reduce
     (fn [current [update base timestamp status]]
       (let [next (publish current source update)]
         (doseq [[index output] (map-indexed vector outputs)]
           (let [result (net/network-cell-strongest next output)
                 expected (if (value/nothing? base) base (+ base (* offset (inc index))))]
             (is (= expected (datum/layer-value result :base)))
             (is (= #{(premise source timestamp status)} (datum/layer-value result :premise-state)))
             (is (= (if (value/nothing? base) #{} #{(premise source timestamp :active)})
                    (datum/support-of result)))))
         (is (= topology (set (keys (net/net-env next)))))
         (is (= next (publish next source update)))
         (is (= next (nb/run-propagators next tasks)))
         next))
     network lifecycle)))

(deftest builder-identities-and-layered-computations-withdraw-and-recover
  (doseq [computation? [false true]]
    (let [[source middle output] (repeatedly 3 ids/new-node-id)
          prepared (nb/install-cells [source middle output])
          [network tasks]
          (if computation?
            (let [base (supported/from-primitive (prop/concrete-primitive-propagator inc))
                  [n1 t1 _] (supported/install base prepared [source] middle)
                  [n2 t2 _] (supported/install base n1 [middle] output)]
              [n2 (into t1 t2)])
            (let [[p1 n1] ((observation/identity ::first source middle) prepared)
                  [p2 n2] ((observation/identity ::second middle output) n1)]
              [n2 [p1 p2]]))]
      (verify-lifecycle {:network (nb/run-propagators network tasks) :tasks tasks
                         :source source :outputs [middle output] :offset (if computation? 1 0)}))))

(deftest compiled-definitions-arguments-results-and-sync-use-current-publication
  (doseq [code ["(let-cell [out] (define value x) (-> value out) out)"
                "(let-cell [out] (define calculate (network (a) (+ a 1))) (-> (calculate x) out) out)"
                "(let-cell [out] (define calculate (network (a) (+ a x))) (-> (calculate 1) out) out)"]]
    (let [source (ids/new-node-id)
          initial (publish (nb/install-cells [source]) source (observed 10 (premise source 1 :active)))
          compiled (compiler/compile-expr-with-bindings
                    (parser/parse-string code)
                    (into (vec (basis/default-bindings))
                          (concat (extension/extension-bindings primitives/session-extension)
                                  [['x (env/cell-binding source)]])) {:net initial})
          network (nb/run-propagators (:net compiled) (:props compiled))]
      (verify-lifecycle {:network network :tasks (:props compiled) :source source
                         :outputs [(:cell compiled)] :offset (if (.contains code "calculate") 1 0)}))))

(deftest when-expression-waits-for-layered-usability
  (let [[source output] (repeatedly 2 ids/new-node-id)
        compiled (compiler/compile-expr-with-bindings
                  (parser/parse-string "(when x (-> 1 target))")
                  (into (vec (basis/default-bindings))
                        [['x (env/cell-binding source)]
                         ['target (env/cell-binding output)]])
                  {:net (nb/install-cells [source output])})
        initial (nb/run-propagators (:net compiled) (:props compiled))
        topology (set (keys (net/net-env initial)))
        pending
        (reduce
         (fn [network [base epoch status]]
           (let [p (premise source epoch status)
                 next (publish network source
                               (collection/content {:base base :support #{p}
                                                    :premise-state #{p}}))]
             (is (value/nothing? (net/network-cell-strongest next output)))
             (is (= topology (set (keys (net/net-env next)))))
             next))
         initial [[value/nothing 1 :active]
                  [value/contradiction 2 :active]
                  [true 3 :retracted]])
        ready (publish pending source (observed false (premise source 4 :active)))]
    ;; `when` is an availability gate, not a truthiness branch. Preserve that
    ;; established distinction; conditional truthiness belongs to `switch`.
    (is (= 1 (net/network-cell-strongest ready output)))
    (is (= ready (nb/run-propagators ready (:props compiled))))))

(deftest identity-does-not-copy-source-retained-conflicts-or-history
  (let [[source other target] (repeatedly 3 ids/new-node-id)
        old (collection/content {:base 10 :support #{(premise source 1 :active)
                                                    (premise other 1 :active)}})
        withdrawn (collection/merge-content old (collection/content
                                                {:premise-state #{(premise source 2 :retracted)}}))
        fresh (collection/merge-content withdrawn (observed 20 (premise source 3 :active)))
        prepared (nb/install-cell (nb/install-cells [source target]) source fresh
                                   (merge/strongest-value fresh net/empty-net))
        [id network] ((observation/identity ::copy source target) prepared)
        result (nb/run-propagators network [id])
        retained (net/network-cell-content result target)]
    (is (= 20 (datum/layer-value (net/network-cell-strongest result target) :base)))
    (is (= 2 (count (:support/observations fresh))))
    (is (= 1 (count (:support/observations retained))))
    (is (= #{20} (set (map :base (:support/observations retained)))))
    (is (not= fresh retained))
    (is (= result (nb/run-propagators result [id])))))

(deftest ordinary-and-legacy-layered-identity-contracts-remain-intact
  (let [[source target] (repeatedly 2 ids/new-node-id)
        p (premise source 1 :active)]
    (doseq [update [42 (collection/content {:base 10 :support #{p}})]]
      (let [network (publish (nb/install-cells [source target]) source update)
            [id installed] ((observation/identity ::copy source target) network)
            result (nb/run-propagators installed [id])
            v (net/network-cell-strongest result target)]
        (is (= (net/network-cell-strongest network source) v))
        (is (not (datum/layer-present? v :premise-state)))))))

(deftest empty-supported-identity-publishes-only-empty-state
  (let [[source target] (repeatedly 2 ids/new-node-id)
        bottom (collection/content {:premise-state #{}})
        network (publish (nb/install-cells [source target]) source bottom)
        [id installed] ((observation/identity ::copy source target) network)
        result (nb/run-propagators installed [id])]
    (is (value/nothing? (datum/layer-value (net/network-cell-strongest result target) :base)))
    (is (= #{} (datum/support-of (net/network-cell-strongest result target))))
    (is (= #{} (datum/layer-value (net/network-cell-strongest result target) :premise-state)))
    (is (= bottom (net/network-cell-content result target)))
    (is (= result (nb/run-propagators result [id])))))

(deftest source-conflict-withdrawal-and-fresher-joint-repair
  (let [[a b output] (repeatedly 3 ids/new-node-id)
        a1 (premise a 1 :active) b1 (premise b 1 :active)
        a2 (premise a 2 :active) ar (premise a 3 :retracted)
        a4 (premise a 4 :active) b2 (premise b 2 :active)
        [id network] ((observation/identity ::copy a output) (nb/install-cells [a output]))
        equal (-> network (publish a (observed 10 a1)) (publish a (observed 10 b1)))
        conflicting (-> network (publish a (observed 10 a1))
                        (publish a (observed 30 b1)) (publish a (observed 20 a2)))
        conflict (net/network-cell-strongest conflicting a)
        withdrawn (publish conflicting a (collection/content {:premise-state #{ar}}))
        joint (collection/content {:base 99 :support #{a4 b2} :premise-state #{a4 b2}})
        repaired (publish withdrawn a joint)]
    (is (= 10 (datum/layer-value (net/network-cell-strongest equal output) :base)))
    (is (= #{a1 b1} (datum/support-of (net/network-cell-strongest equal output))))
    (is (value/contradiction? (datum/layer-value conflict :base)))
    (is (= #{a2 b1} (datum/support-of conflict)))
    (is (= #{a2 b1} (datum/layer-value conflict :premise-state)))
    (is (value/contradiction? (datum/layer-value (net/network-cell-strongest conflicting output) :base)))
    (is (= #{a2 b1} (datum/support-of (net/network-cell-strongest conflicting output))))
    (is (= #{a2 b1} (datum/layer-value (net/network-cell-strongest conflicting output) :premise-state)))
    (is (= 30 (datum/layer-value (net/network-cell-strongest withdrawn output) :base)))
    (is (= #{b1} (datum/support-of (net/network-cell-strongest withdrawn output))))
    (is (= #{ar b1} (datum/layer-value (net/network-cell-strongest withdrawn output) :premise-state)))
    (doseq [cell [a output]]
      (is (= 99 (datum/layer-value (net/network-cell-strongest repaired cell) :base)))
      (is (= #{a4 b2} (datum/support-of (net/network-cell-strongest repaired cell)))))
    (is (= repaired (publish repaired a (observed 20 a2))))
    (is (= repaired (nb/run-propagators repaired [id])))))
