(ns examples.lain.visualization-combinators.ttms-transport-audit
  "Read-only runtime probes for TTMS transport boundaries. No replacement policy."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.model.env :as env]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as standard]))

(defn- premise [source epoch status]
  {:source source :timestamp epoch :premises-status status})

(defn- view [network id]
  (let [v (net/network-cell-strongest network id)]
    {:base (if (datum/layer-present? v :base) (datum/layer-value v :base) v)
     :support (datum/support-of v)
     :empty? (value/nothing? v)
     :usable? (not (value/unusable? v))}))

(defn- publish [network source epoch status base]
  (let [update (collection/content
                {:base base :support #{(premise source epoch status)}})
        [tasks patched] (core/eval-cell source (message/message source update) network)]
    (nb/run-propagators patched tasks)))

(defn- transport-probe [source label base support]
  (let [[target owner slot-target] (repeatedly 3 ids/new-node-id)
        n0 (nb/install-cells [source target owner slot-target])
        [_ n1] (core/eval-cell source
                 (message/message source (collection/content {:base base :support support})) n0)
        observed (net/network-cell-strongest n1 source)
        [id installed] ((standard/id source target) n1)
        patches ((prop/prop-f (net/network-env-lookup installed id))
                 [source] [target] installed)
        forward (basis/mono-sync-messages n1 source target)
        compound (obj/compound-object {:x observed})
        seeded (nb/install-cell n1 owner compound compound)
        [slot-id slotted] ((obj/p:network-slot :x slot-target owner) seeded)
        result (nb/run-propagators slotted [slot-id])]
    (assert (= 1 (count patches)))
    (assert (= #{{:base base :support support}}
               (:support/observations (message/message-value (first patches)))))
    (assert (= 1 (count forward)))
    (assert (= base (:base (view result slot-target))))
    (assert (= support (:support (view result slot-target))))
    {:case label :identity-patches (count patches)
     :compiler-forward-patches (count forward)
     :compound-slot (view result slot-target)}))

(defn transport-probes []
  (let [source (ids/new-node-id)]
    (mapv (fn [[label base support]] (transport-probe source label base support))
          [[:active 10 #{(premise source 1 :active)}]
           [:retracted value/nothing #{(premise source 2 :retracted)}]
           [:contradiction value/contradiction #{(premise source 1 :active)}]
           [:mixed 30 #{(premise source 1 :active) (premise source 2 :active)}]])))

(defn lexical-probe []
  (let [source (ids/new-node-id)
        initial (publish (nb/install-cells [source]) source 1 :active 10)
        compiled (compiler/compile-expr-with-bindings
                  (parser/parse-string "x") [['x (env/cell-binding source)]]
                  {:net initial})
        active (nb/run-propagators (:net compiled) (:props compiled))
        updated (publish active source 2 :active 20)
        withdrawn (publish updated source 3 :retracted value/nothing)
        restored (publish withdrawn source 4 :active 7)]
    {:source source :output (:cell compiled)
     :stages (mapv (fn [[stage network]]
                     {:stage stage :source (view network source)
                      :output (view network (:cell compiled))})
                   [[:active active] [:updated updated]
                    [:retracted withdrawn] [:reactivated restored]])}))

(defn -main [& _]
  (doseq [probe (transport-probes)] (prn probe))
  (prn {:lexical (lexical-probe)})
  (shutdown-agents))
