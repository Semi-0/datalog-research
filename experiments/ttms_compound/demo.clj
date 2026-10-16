(ns experiments.ttms-compound.demo
  "Run with clojure -M -m experiments.ttms-compound.demo."
  (:require [clojure.pprint :refer [pprint]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-compound :as compound]
            [propagators.experimental.ttms-reducer :as history]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- publish [network target epoch status base]
  (let [p {:source target :timestamp epoch :premises-status status}
        update (collection/content {:base base :support #{p} :premise-state #{p}})
        [tasks patched] (core/eval-cell target (message/message target update) network)]
    (nb/run-propagators patched tasks)))

(defn- base [network id]
  (let [v (datum/layer-value (net/network-cell-strongest network id) :base)]
    (if (evidence/evidence-set? v)
      (evidence/strongest v)
      v)))

(defn- printable [v]
  (cond
    (value/nothing? v) 'the-nothing
    (value/contradiction? v) 'the-contradiction
    :else v))

(defn- summary [network owner x y output]
  (let [recording (base network output)]
    {:members (set (obj/public-slot-keys (base network owner)))
     :x (printable (base network x))
     :y (printable (base network y))
     :record-count (count (:records recording))
     :current-record-count (count (:current recording))
     :original-epochs (mapv #(-> % :support first :timestamp) (:records recording))}))

(defn dependency-shrink-counterexample
  "A transparent reduction can lose its new result under existing TTMS dominance."
  []
  (let [[a b] (repeatedly 2 ids/new-node-id)
        network (nb/install-cells [a b])
        a1 {:source a :timestamp 1 :premises-status :active}
        a2 {:source a :timestamp 2 :premises-status :retracted}
        b1 {:source b :timestamp 1 :premises-status :active}
        old (collection/content {:base (obj/compound-object [7 8])
                                 :support #{a1 b1} :premise-state #{a1 b1}})
        fresh (collection/content {:base (obj/compound-object [8])
                                   :support #{b1} :premise-state #{a2 b1}})
        content (collection/merge-content old fresh)
        projected (merge/strongest-value content network)
        actual (datum/layer-value projected :base)]
    {:expected-current [8]
     :actual-current (printable actual)
     :new-result-survived (not (value/nothing? actual))
     :retained-observations (count (:support/observations content))}))

(defn run-demo []
  (let [[owner x y records archive output] (repeatedly 6 ids/new-node-id)
        initial (nb/install-cells [owner x y records archive output])
        [px n1] ((compound/p:slot :x x owner) initial)
        [py n2] ((compound/p:slot :y y owner) n1)
        [collectors n3] ((history/p:collect owner records archive output) n2)
        tasks (into [px py] collectors)
        network (nb/run-propagators n3 tasks)
        old (obj/compound-object {:x 10 :y 20})
        fresh (obj/compound-object {:x 7})
        seeded (publish network owner 1 :active old)
        replaced (publish seeded owner 2 :active fresh)
        old-support #{{:source owner :timestamp 1 :premises-status :active}}
        [feedback-tasks feedback-net]
        (core/eval-cell y (message/message y (collection/content
                                             {:base 20 :support old-support
                                              :premise-state old-support})) replaced)
        replayed (nb/run-propagators feedback-net feedback-tasks)
        withdrawn (publish replayed owner 3 :retracted value/nothing)
        restored (publish withdrawn owner 4 :active (obj/compound-object {:x 99}))]
    {:initial (summary seeded owner x y output)
     :replaced (summary replaced owner x y output)
     :stale-feedback-rejected (= replaced replayed)
     :withdrawn (summary withdrawn owner x y output)
     :restored (summary restored owner x y output)
     :rerun-is-fixed-point (= restored (nb/run-propagators restored tasks))
     :runtime-node-counts [(count (net/net-env network)) (count (net/net-env restored))]
     :recording-source-is-archive
     (= #{archive} (set (map :source (datum/support-of
                                     (net/network-cell-strongest restored output)))))}))

(defn -main [& _args]
  (pprint (assoc (run-demo) :dependency-shrink-counterexample
                (dependency-shrink-counterexample))))
