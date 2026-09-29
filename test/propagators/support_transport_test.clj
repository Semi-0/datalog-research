(ns propagators.support-transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.boundary :as boundary]
            [propagators.stdlib.prop :as standard]))

(defn- premise [source timestamp status]
  {:source source :timestamp timestamp :premises-status status})

(defn- observation [base & premises]
  (collection/content {:base base :support (set premises)}))

(defn- publish [network target content]
  (let [[tasks patched] (core/eval-cell target (message/message target content) network)]
    (nb/run-propagators patched tasks)))

(defn- compiler-forward [from to]
  (prop/construct-propagator
   :test/compiler-forward
   (fn [_ _ network] (basis/mono-sync-messages network from to))
   [from] [to]))

(defn- slot-link [from to]
  (fn [network]
    (let [owner (ids/new-node-id)
          prepared (nb/install-cell network owner)
          [left n1] ((obj/p:network-slot :x from owner) prepared)
          [right n2] ((obj/p:network-slot :x to owner) n1)]
      [[left right] n2])))

(defn- chain [link]
  (let [[source middle output] (repeatedly 3 ids/new-node-id)
        [first-task n1] ((link source middle) (nb/install-cells [source middle output]))
        [second-task n2] ((link middle output) n1)
        tasks (vec (flatten [first-task second-task]))]
    {:source source :middle middle :output output :tasks tasks
     :network (nb/run-propagators n2 tasks)}))

(defn- assert-value [network target base support]
  (let [actual (net/network-cell-strongest network target)]
    (is (= base (datum/layer-value actual :base)))
    (is (= support (datum/support-of actual)))
    (is (collection/content? (net/network-cell-content network target)))))

(deftest two-links-withdraw-and-recover-without-new-topology
  (doseq [[label link] [[:identity standard/id]
                       [:compiler compiler-forward]
                       [:boundary (fn [a b] #(boundary/fast-bi-sync % a b))]
                       [:compound slot-link]]]
    (testing (name label)
      (let [{:keys [source middle output network tasks]} (chain link)
            a1 (premise source 1 :active)
            a2 (premise source 2 :active)
            a3 (premise source 3 :retracted)
            a4 (premise source 4 :active)
            states (reductions #(publish %1 source %2) network
                               [(observation 10 a1) (observation 20 a2)
                                (observation value/nothing a3) (observation 7 a4)])]
        (doseq [[n base support] (map vector (rest states)
                                    [10 20 value/nothing 7] [#{a1} #{a2} #{a3} #{a4}])
                target [middle output]]
          (assert-value n target base support))
        (is (value/unusable? (net/network-cell-strongest (nth states 3) output)))
        (let [final (last states)
              replayed (publish final source (observation 10 a1))
              rerun (nb/run-propagators final tasks)]
          (is (= (net/net-env final) (net/net-env replayed)))
          (is (= (net/net-env final) (net/net-env rerun)))
          (is (= (set (keys (net/net-env network)))
                 (set (keys (net/net-env final))))))))))

(deftest compound-peers-preserve-separate-evidence-and-support-only-updates
  (let [{:keys [network source middle output]} (chain slot-link)
        a (premise source 1 :active)
        b (premise output 1 :active)
        active (-> network (publish source (observation 10 a))
                   (publish output (observation 20 b)))
        ar (premise source 2 :retracted)
        withdrawn (publish active source (observation value/nothing ar))
        a3 (premise source 3 :active)
        recovered (publish withdrawn source (observation 20 a3))]
    (doseq [target [source middle output]]
      (assert-value active target value/contradiction #{a b})
      (is (= 2 (count (:support/observations (net/network-cell-content active target)))))
      (assert-value withdrawn target 20 #{ar b})
      (is (value/unusable? (net/network-cell-strongest withdrawn target)))
      (assert-value recovered target 20 #{a3 b})
      (is (not (value/unusable? (net/network-cell-strongest recovered target)))))))

(deftest initial-unusable-slot-values-still-carry-dependencies
  (doseq [base [value/nothing value/contradiction 30]]
    (let [[source parent owner] (repeatedly 3 ids/new-node-id)
          support #{(premise source 1 :active) (premise source 2 :active)}
          v (obj/compound-object {:base base :support support})
          n0 (nb/seed-cell (nb/install-cells [parent owner]) owner
                          (obj/compound-object {:x v}))
          [task installed] ((obj/p:network-slot :x parent owner) n0)
          result (nb/run-propagators installed [task])]
      (assert-value result parent base support))))

(deftest ordinary-publication-and-retained-evidence
  (doseq [v [1 false value/nothing value/contradiction {:base 10 :label :ordinary}]]
    (is (= v (standard/forward-value ::content v))))
  (let [source (ids/new-node-id)
        a (premise source 1 :active)
        retained (collection/merge-content (observation 10 a) (observation 20 a))]
    (is (identical? retained (standard/forward-value retained ::projection)))
    (is (= (observation value/nothing a)
           (standard/forward-value ::content {:base value/nothing :support #{a}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (standard/forward-value ::content
                   {:base 10 :support #{a} :extra :must-not-be-discarded})))))

(deftest compiled-links-and-compound-network-withdraw-and-recover
  (doseq [program ["(let-cell [a b] (-> x a) (-> a b) b)"
                   "(let-cell [out]
                      (def-net copy [input] [output] (-> input output))
                      (copy x out) out)"
                   "(let-cell [middle out]
                      (def-net copy [input] [output] (-> input output))
                      (copy x middle) (copy middle out) out)"]]
    (testing program
      (let [source (ids/new-node-id)
            compiled (compiler/compile-expr-with-bindings
                      (parser/parse-string program)
                      (conj (vec (basis/default-bindings)) ['x (env/cell-binding source)])
                      {:net (nb/install-cells [source])})
            initial (nb/run-propagators (:net compiled) (:props compiled))]
        (reduce (fn [network [timestamp status base]]
                  (let [p (premise source timestamp status)
                        after (publish network source (observation base p))]
                    (assert-value after (:cell compiled) base #{p})
                    after))
                initial [[1 :active 10] [2 :active 20]
                         [3 :retracted value/nothing] [4 :active 7]])))))

(deftest application-boundary-preserves-ordinary-readiness-and-content
  (doseq [[content strongest expected]
          [[value/nothing value/nothing []]
           [value/contradiction value/contradiction []]
           [false false [false]]
           [10 10 [10]]
           [{:retained :opaque} 10 [{:retained :opaque}]]]]
    (let [[source target app] (repeatedly 3 ids/new-node-id)
          prepared (-> (nb/install-cells [target])
                       (nb/install-cell source content strongest))
          [id installed] (((application/concrete-boundary app :inbound 0)
                           source target) prepared)
          patches ((prop/prop-f (net/network-env-lookup installed id))
                   [source] [target] installed)]
      (is (= expected (mapv message/message-value patches))))))
