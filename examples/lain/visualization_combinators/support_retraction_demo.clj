(ns examples.lain.visualization-combinators.support-retraction-demo
  "Headless support transport: source -> (+ 20) -> (+ 5). No compiler changes."
  (:require [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.stdlib.arithmetic.base :as base]
            [propagators.stdlib.support :as support]))

(defn- addition [network]
  (let [[procedure base-id support-id] (repeatedly 3 ids/new-node-id)
        prepared (-> network
                     (nb/install-cell base-id base/plus-closure base/plus-closure)
                     (nb/install-cell support-id support/procedure support/procedure))
        with-base (layered/install-layered-procedure! prepared procedure :base base-id)
        with-support (layered/install-layered-procedure!
                      (:net with-base) procedure :support support-id)]
    [procedure (:net with-support)]))

(defn chain []
  (let [[source twenty five middle output] (repeatedly 5 ids/new-node-id)
        declared (-> net/empty-net
                    (nb/install-cell source)
                    (nb/install-cell twenty 20 20)
                    (nb/install-cell five 5 5)
                    (nb/install-cell middle)
                    (nb/install-cell output))
        bottom (collection/content {:base value/nothing :support #{}})
        initial (reduce (fn [network id]
                          (second (core/eval-cell id (message/message id bottom) network)))
                        declared [source middle output])
        [procedure prepared] (addition initial)
        [network tasks]
        (reduce
         (fn [[network tasks] [arguments output]]
           (let [[id installed]
                 ((layered/p:apply-layered procedure arguments output) network)
                 application (net/network-env-lookup installed id)
                 activation (prop/compose-activation
                             (prop/prop-f application)
                             (message/lift-message collection/content))]
             [(net/assoc-net-prop installed id (assoc application :activate activation))
              (conj tasks id)]))
         [prepared []] [[[source twenty] middle] [[middle five] output]])]
    {:network (nb/run-propagators network tasks)
     :source source :middle middle :output output :tasks tasks}))

(defn publish
  "Merge an explicit source observation and drain the ordinary runner."
  [chain timestamp status base]
  (let [source (:source chain)
        update (collection/content
                {:base base
                 :support #{{:source source :timestamp timestamp
                             :premises-status status}}})
        [tasks network] (core/eval-cell source (message/message source update)
                                        (:network chain))]
    (assoc chain :network
           (runner/completed-network (runner/run-network tasks network)))))

(defn result [chain]
  (let [v (net/network-cell-strongest (:network chain) (:output chain))]
    {:base (datum/layer-value v :base)
     :support (datum/support-of v)
     :usable? (not (value/unusable? v))}))

(defn -main [& _]
  (let [start (chain)
        active (publish start 1 :active 10)
        updated (publish active 2 :active 20)
        retracted (publish updated 3 :retracted value/nothing)
        restored (publish retracted 4 :active 7)]
    (doseq [[stage state] [[:active active] [:updated updated]
                          [:retracted retracted] [:reactivated restored]]]
      (prn stage (result state)))
    (shutdown-agents)))
