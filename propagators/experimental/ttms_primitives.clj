(ns propagators.experimental.ttms-primitives
  "Opt-in scalar procedures and branching. Compiler defaults remain unchanged."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-branching :as branching]
            [propagators.experimental.premise-publication :as publication]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.layered.procedure :as procedure]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as standard]
            [propagators.stdlib.support :as support]
            [propagators.stdlib.premise-state :as state]))

(defn- scalar-primitive [name f]
  (prop/primitive-propagator
   name
   (fn [& values]
     (cond
       (some value/contradiction? values)
       (let [reasons (into #{} (mapcat value/contradiction-provenance) values)]
         (value/contradiction-with-provenance reasons))
       (some value/nothing? values) value/nothing
       :else (try (apply f values)
                  (catch Exception error
                    (value/contradiction-with-provenance
                     #{{:operation name :reason (.getMessage error)}})))))))

(defn- base-procedure [primitive]
  {:net net/empty-net
   :f (fn [_ inputs outputs network]
        (second ((apply primitive (concat inputs outputs)) network)))})

(defn- publish [v]
  (if (datum/layer-present? v :premise-state)
    (publication/content v)
    (collection/content
   (if (datum/layer-present? v :support)
     v
     {:base (datum/layer-value v :base) :support #{}}))))

(defn- install-call [base network arguments output]
  (let [base (procedure/base base)
        [procedure base-id support-id state-id] (repeatedly 4 ids/new-node-id)
        prepared (-> (reduce nb/ensure-cell network (conj (vec arguments) output))
                     (nb/install-cell base-id base base)
                     (nb/install-cell support-id support/procedure support/procedure)
                     (nb/install-cell state-id state/procedure state/procedure))
        base-layer (layered/install-layered-procedure! prepared procedure :base base-id)
        support-layer (layered/install-layered-procedure!
                       (:net base-layer) procedure :support support-id)
        state-layer (layered/install-layered-procedure! (:net support-layer) procedure :premise-state state-id)
        [id installed] ((layered/p:apply-layered procedure arguments output) (:net state-layer))
        application (net/network-env-lookup installed id)
        activation (prop/compose-activation (prop/prop-f application)
                                             (message/lift-message publish)
                                             publication/transport-states)]
    [(net/assoc-net-prop installed id (assoc application :activate activation)) [id] output]))

(defn procedure-operator
  "Compose an existing base procedure with the support and premise-state layers."
  [name base]
  (operator/operator-closure
   {:name [::scalar name]
    :install (fn [network arguments output]
               (install-call base network arguments output))}))

(defn scalar-operator [name primitive]
  (procedure-operator name (base-procedure primitive)))

(defn slot-operator
  "Read a slot from the base compound through an ordinary layered application.
  The base's slots are separate from the datum's base/support layers. This
  publishes a derived result; it does not grant writes into the TTMS owner.
  Experimental: nested supported slots and domain :support fields still fail
  the compound-boundary tests in shared transport/readiness. Not a replacement
  for the ordinary bidirectional slot operator."
  [slot]
  (scalar-operator
   [::slot slot]
   (scalar-primitive
    [::slot slot]
    (fn [base]
      (let [compound (if (evidence/evidence-set? base)
                       (evidence/strongest base)
                       base)]
        (cond
          (value/contradiction? compound) compound
          (contains? (obj/public-slot-keys compound) slot)
          (obj/slot-value compound slot)
          :else value/nothing))))))

(def session-extension
  (extension/extension-bundle
   {:id ::scalars
    :bindings
    (into (extension/extension-bindings branching/session-extension)
          (map (fn [[name primitive]] [name (scalar-operator name primitive)]))
          [['+ standard/+] ['- standard/-] ['* standard/*] ['/ standard//]
           ['<= (scalar-primitive '<= <=)] ['not (scalar-primitive 'not not)]
           ['and standard/and] ['or standard/or]
           ['< (scalar-primitive '< <)] ['> (scalar-primitive '> >)]
           ['>= (scalar-primitive '>= >=)] ['= (scalar-primitive '= =)]
           ['str (scalar-primitive 'str str)]])
    :effects []}))
