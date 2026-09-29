(ns propagators.ttms-primitives-test
  (:require [clojure.test :refer [deftest is]]
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
            [propagators.network-builder :as nb]))

(defn- publish [network source epoch status base]
  (let [content (collection/content
                 {:base base :support #{{:source source :timestamp epoch :premises-status status}}})
        [tasks patched] (core/eval-cell source (message/message source content) network)]
    (nb/run-propagators patched tasks)))

(defn- compile-chain [source]
  (let [input (ids/new-node-id)
        initial (publish (nb/install-cells [input]) input 1 :active 10)
        compiled (compiler/compile-expr-with-bindings
                  (parser/parse-string source)
                  (into (vec (basis/default-bindings))
                        (concat (extension/extension-bindings primitives/session-extension)
                                [['x (env/cell-binding input)]]))
                  {:net initial})]
    {:source input :output (:cell compiled) :tasks (:props compiled)
     :network (nb/run-propagators (:net compiled) (:props compiled))}))

(deftest scalar-bindings-use-layered-procedures
  (doseq [[source expected] [["(+ x 2)" 12] ["(- x 2)" 8] ["(* x 2)" 20]
                           ["(/ x 2)" 5] ["(<= x 10)" true] ["(< x 10)" false]
                           ["(> x 9)" true] ["(>= x 10)" true] ["(= x 10)" true]
                           ["(not (= x 10))" false] ["(not x)" false]
                           ["(<= 1 x 20)" true] ["(<= x)" true] ["(str x)" "10"]
                           ["(and (= x 10) true)" true] ["(or (= x 9) true)" true]]]
    (let [{:keys [network output source]} (compile-chain source)
          result (net/network-cell-strongest network output)]
      (is (= expected (datum/layer-value result :base)))
      (is (= #{{:source source :timestamp 1 :premises-status :active}}
             (datum/support-of result))))))

(deftest compiled-arithmetic-conditional-and-network-definition-lifecycle
  (doseq [code ["(if (<= x 10) (+ x 1) (* x 2))"
                "(let-cell [out]
                   (def-net calculate [a] [b] (-> (+ a 1) b))
                   (calculate x out)
                   out)"
                "(let-cell [out]
                   (def-net captured [a] [b] (-> (+ a x) b))
                   (captured 1 out)
                   out)"]]
    (let [{:keys [network source output tasks]} (compile-chain code)
          updated (publish network source 2 :active 20)
          withdrawn (publish updated source 3 :retracted value/nothing)
          restored (publish withdrawn source 4 :active 7)
          expected-update (if (= code "(if (<= x 10) (+ x 1) (* x 2))") 40 21)]
      (doseq [[n epoch status expected] [[network 1 :active 11]
                                         [updated 2 :active expected-update]
                                         [withdrawn 3 :retracted value/nothing]
                                         [restored 4 :active 8]]]
        (let [v (net/network-cell-strongest n output)]
          (is (= expected (datum/layer-value v :base)))
          (is (= #{{:source source :timestamp epoch :premises-status status}}
                 (datum/support-of v)))))
      (is (= (set (keys (net/net-env network))) (set (keys (net/net-env restored)))))
      (is (= (net/net-env restored) (net/net-env (nb/run-propagators restored tasks)))))))
