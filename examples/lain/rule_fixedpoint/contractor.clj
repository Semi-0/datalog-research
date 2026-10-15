(ns examples.lain.rule-fixedpoint.contractor
  "One genuine three-way x+y=z relation. Base computation uses layered procedures;
  domain transport/publication and premise fanout are separate propagators."
  (:require [examples.lain.amb.domain-refinement :as domain]
            [examples.lain.relational-datalog.runtime :as runtime]
            [examples.lain.rule-fixedpoint.channels :as channels]
            [examples.lain.rule-fixedpoint.domain-policy :as policy]
            [examples.lain.rule-fixedpoint.evidence :as evidence]
            [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.gur.flat :as gur]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.supported-procedure :as supported]))

(defn- add-prop [world installer]
  (let [[id network] (installer (:network world))]
    (assoc world :network network :tasks (tq/enqueue (:tasks world) id))))

(defn- forward [source port]
  (prop/construct-propagator
   ::assertion
   (fn [_ _ network]
     [(message/message port (policy/content {source (net/network-cell-content network source)}))])
   [source] [port]))

(defn- states [sources ports]
  (prop/construct-propagator
   ::state-transport
   (fn [_ _ network]
     (into [] (keep (fn [port]
                      (let [updates (policy/state-updates network port)]
                        (if (seq updates) (message/message port (policy/content updates)) nil)))) ports))
   (into sources ports) ports))

(defn- publish [computed ports]
  (prop/construct-propagator
   ::publish-domain
   (fn [_ _ network]
     (let [v (net/network-cell-strongest network computed)
           base (datum/layer-value v :base) support (datum/support-of v)]
       (if (and (vector? base) (evidence/current? network support))
         (mapv (fn [port candidates]
                 (let [id (gur/stable-node-id [::addition port (evidence/source-key support)])]
                   (message/message port
                                    (policy/content {id (evidence/derived (domain/domain candidates) support)}))))
               ports base)
         [])))
   [computed] ports))

(defn- retain-computation [computed result]
  ;; Preserve alternatives before TTMS compaction, via activation composition.
  (let [observations (:support/observations result) states (:support/states result)]
    (policy/content
     (if (seq observations)
       (into {} (map (fn [observation]
                       [(gur/stable-node-id [::computation computed (evidence/source-key (:support observation))])
                        {:support/observations #{observation} :support/states states}])) observations)
       {(gur/stable-node-id [::states computed]) result}))))

(defn prepare [bound]
  (if (and (integer? bound) (pos? bound))
    (let [ports (vec (repeatedly 3 ids/new-node-id))
          sources (zipmap [:bound-x :bound-y :bound-z :y :z] (repeatedly ids/new-node-id))
          computed (ids/new-node-id)
          initial (nb/install-cells (concat ports (vals sources) [computed]))
          initial (reduce #(nb/install-cell %1 %2 policy/empty-content (policy/strongest policy/empty-content))
                          initial (conj ports computed))
          initial (reduce #(nb/install-cell %1 %2 (collection/content {:premise-state #{}}) value/nothing)
                          initial (vals sources))
          base (supported/from-primitive
                (prop/primitive-propagator
                 ::addition
                 (fn [x y z]
                   (if (some value/unusable? [x y z]) value/nothing
                       (domain/projections (fn [a b c] (= (+ a b) c))
                                           (mapv domain/candidates [x y z]))))))
          [network tasks _] (supported/install base initial ports computed)
          application (net/network-env-lookup network (first tasks))
          activated (prop/compose-activation (prop/prop-f application)
                                            (message/lift-message #(retain-computation computed %)))
          network (net/assoc-net-prop network (first tasks) (assoc application :activate activated))
          world {:network network :tasks (tq/into-queue tasks) :sources sources :ports ports
                 :computed computed :bound bound}
          world (reduce (fn [w [source port]] (add-prop w (forward (get sources source) port)))
                        world (map vector [:bound-x :bound-y :bound-z :y :z]
                                   (concat ports [(second ports) (last ports)])))]
      (-> world (add-prop (states (vec (vals sources)) (conj ports computed)))
          (add-prop (publish computed ports))))
    (throw (ex-info "A positive finite bound is required" {:bound bound}))))

(defn inject [world name candidates version]
  (let [source (get-in world [:sources name])
        base (if (= :retracted (:status version)) value/nothing (domain/domain candidates))]
    (if source
      (channels/tell world source (evidence/observation source (:timestamp version) (:status version) base))
      (throw (ex-info "Unknown contractor source" {:source name})))))

(defn seed [world]
  (reduce #(inject %1 %2 (range (inc (:bound world))) {:timestamp 1 :status :active})
          world [:bound-x :bound-y :bound-z]))

(defn run [world]
  (merge world (runtime/run (:network world) (:tasks world)
                            {:evaluate-patches policy/evaluate-patches :max-steps 10000 :max-ms 30000})
         {:tasks tq/empty-queue}))

(defn domains [world]
  (mapv #(datum/layer-value (net/network-cell-strongest (:network world) %) :base) (:ports world)))
